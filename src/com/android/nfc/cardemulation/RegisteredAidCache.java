/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/******************************************************************************
*
*  The original Work has been changed by NXP.
*
*  Licensed under the Apache License, Version 2.0 (the "License");
*  you may not use this file except in compliance with the License.
*  You may obtain a copy of the License at
*
*  http://www.apache.org/licenses/LICENSE-2.0
*
*  Unless required by applicable law or agreed to in writing, software
*  distributed under the License is distributed on an "AS IS" BASIS,
*  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
*  See the License for the specific language governing permissions and
*  limitations under the License.
*
*  Copyright 2018-2020 NXP
*
******************************************************************************/
package com.android.nfc.cardemulation;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.nfc.cardemulation.NfcApduServiceInfo;
import android.nfc.cardemulation.CardEmulation;
import android.util.Log;

import com.google.android.collect.Maps;
import java.util.Collections;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.PriorityQueue;
import java.util.TreeMap;
import android.nfc.cardemulation.NfcAidGroup;
import com.android.nfc.NfcService;
import com.nxp.nfc.NfcConstants;
import android.os.SystemProperties;
public class RegisteredAidCache {
    static final String TAG = "RegisteredAidCache";

    static final boolean DBG = ((SystemProperties.get("persist.nfc.ce_debug").equals("1")) ? true : false);

    static final int AID_ROUTE_QUAL_SUBSET = 0x20;
    static final int AID_ROUTE_QUAL_PREFIX = 0x10;
    // mAidServices maps AIDs to services that have registered them.
    // It's a TreeMap in order to be able to quickly select subsets
    // of AIDs that conflict with each other.
    final TreeMap<String, ArrayList<ServiceAidInfo>> mAidServices =
            new TreeMap<String, ArrayList<ServiceAidInfo>>();

    // mAidCache is a lookup table for quickly mapping an exact or prefix or subset AID
    // to one or more handling services. It differs from mAidServices in the sense that it
    // has already accounted for defaults, and hence its return value
    // is authoritative for the current set of services and defaults.
    // It is only valid for the current user.
    final TreeMap<String, AidResolveInfo> mAidCache = new TreeMap<String, AidResolveInfo>();
    //FIXME: directly use the declaration in ApduServerInfo in framework
    static final int POWER_STATE_SWITCH_ON = 1;
    static final int POWER_STATE_SWITCH_OFF = 2;
    static final int POWER_STATE_BATTERY_OFF = 4;
    static final int POWER_STATE_ALL = POWER_STATE_SWITCH_ON | POWER_STATE_SWITCH_OFF |POWER_STATE_BATTERY_OFF ;
    static final int SCREEN_STATE_OFF_UNLOCKED = 0x08;
    static final int SCREEN_STATE_ON_LOCKED = 0x10;
    static final int SCREEN_STATE_OFF_LOCKED = 0x20;
    static final int SCREEN_STATE_INVALID = 0x00;
    static final int SCREEN_STATE_DEFAULT_MASK = 0x16;
    // Represents a single AID registration of a service
    final class ServiceAidInfo {
        NfcApduServiceInfo service;
        String aid;
        String category;

        @Override
        public String toString() {
            return "ServiceAidInfo{" +
                    "service=" + service.getComponent() +
                    ", aid='" + aid + '\'' +
                    ", category='" + category + '\'' +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            ServiceAidInfo that = (ServiceAidInfo) o;

            if (!aid.equals(that.aid)) return false;
            if (!category.equals(that.category)) return false;
            if (!service.equals(that.service)) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = service.hashCode();
            result = 31 * result + aid.hashCode();
            result = 31 * result + category.hashCode();
            return result;
        }
    }

    // Represents a list of services, an optional default and a category that
    // an AID was resolved to.
    final class AidResolveInfo {
        List<NfcApduServiceInfo> services = new ArrayList<NfcApduServiceInfo>();
        NfcApduServiceInfo defaultService = null;
        String category = null;
        boolean mustRoute = true; // Whether this AID should be routed at all
        ReslovedPrefixConflictAid prefixInfo = null;
        @Override
        public String toString() {
            return "AidResolveInfo{" +
                    "services=" + services +
                    ", defaultService=" + defaultService +
                    ", category='" + category + '\'' +
                    ", mustRoute=" + mustRoute +
                    '}';
        }
    }

    final AidResolveInfo EMPTY_RESOLVE_INFO = new AidResolveInfo();

    final Context mContext;
    final AidRoutingManager mRoutingManager;

    final Object mLock = new Object();

    ComponentName mPreferredPaymentService;
    ComponentName mPreferredForegroundService;
    boolean mNfcEnabled = false;
    boolean mSupportsPrefixes = false;
    boolean mSupportsSubset = false;

    public RegisteredAidCache(Context context) {
        mContext = context;
        mRoutingManager = NfcService.getInstance().getAidRoutingCache();
        mPreferredPaymentService = null;
        mPreferredForegroundService = null;
        mSupportsPrefixes = mRoutingManager.supportsAidPrefixRouting();
        mSupportsSubset   = mRoutingManager.supportsAidSubsetRouting();
        if (mSupportsPrefixes) {
            if (DBG) Log.d(TAG, "Controller supports AID prefix routing");
        }
        if (mSupportsSubset) {
            if (DBG) Log.d(TAG, "Controller supports AID subset routing");
        }
    }

    public AidResolveInfo resolveAid(String aid) {
        synchronized (mLock) {
            if (DBG) Log.d(TAG, "resolveAid: resolving AID " + aid);
            if (aid.length() < 10) {
                Log.e(TAG, "AID selected with fewer than 5 bytes.");
                return EMPTY_RESOLVE_INFO;
            }
            AidResolveInfo resolveInfo = new AidResolveInfo();
            if (mSupportsPrefixes || mSupportsSubset) {
                // Our AID cache may contain prefixes/subset which also match this AID,
                // so we must find all potential prefixes or suffixes and merge the ResolveInfo
                // of those prefixes plus any exact match in a single result.
                String shortestAidMatch = aid.substring(0, 10); // Minimum AID length is 5 bytes
                String longestAidMatch = String.format("%-32s", aid).replace(' ', 'F');


                if (DBG) Log.d(TAG, "Finding AID registrations in range [" + shortestAidMatch +
                        " - " + longestAidMatch + "]");
                NavigableMap<String, AidResolveInfo> matchingAids =
                        mAidCache.subMap(shortestAidMatch, true, longestAidMatch, true);

                resolveInfo.category = CardEmulation.CATEGORY_OTHER;
                for (Map.Entry<String, AidResolveInfo> entry : matchingAids.entrySet()) {
                    boolean isPrefix = isPrefix(entry.getKey());
                    boolean isSubset = isSubset(entry.getKey());
                    String entryAid = (isPrefix || isSubset) ? entry.getKey().substring(0,
                            entry.getKey().length() - 1):entry.getKey(); // Cut off '*' if prefix
                    if (entryAid.equalsIgnoreCase(aid) || (isPrefix && aid.startsWith(entryAid))
                            || (isSubset && entryAid.startsWith(aid))) {
                        if (DBG) Log.d(TAG, "resolveAid: AID " + entry.getKey() + " matches.");
                        AidResolveInfo entryResolveInfo = entry.getValue();
                        if (entryResolveInfo.defaultService != null) {
                            if (resolveInfo.defaultService != null) {
                                // This shouldn't happen; for every prefix we have only one
                                // default service.
                                Log.e(TAG, "Different defaults for conflicting AIDs!");
                            }
                            resolveInfo.defaultService = entryResolveInfo.defaultService;
                            resolveInfo.category = entryResolveInfo.category;
                        }
                        for (NfcApduServiceInfo serviceInfo : entryResolveInfo.services) {
                            if (!resolveInfo.services.contains(serviceInfo)) {
                                resolveInfo.services.add(serviceInfo);
                            }
                        }
                    }
                }
            } else {
                resolveInfo = mAidCache.get(aid);
            }
            if (DBG) Log.d(TAG, "Resolved to: " + resolveInfo);
            return resolveInfo;
        }
    }

    public ComponentName getPreferredPaymentService(){
        return mPreferredPaymentService;
    }

    public boolean supportsAidPrefixRegistration() {
        return mSupportsPrefixes;
    }

    public boolean supportsAidSubsetRegistration() {
        return mSupportsSubset;
    }

    public boolean isDefaultServiceForAid(int userId, ComponentName service, String aid) {
        AidResolveInfo resolveInfo = resolveAid(aid);
        if (resolveInfo == null || resolveInfo.services == null ||
                resolveInfo.services.size() == 0) {
            return false;
        }

        if (resolveInfo.defaultService != null) {
            return service.equals(resolveInfo.defaultService.getComponent());
        } else if (resolveInfo.services.size() == 1) {
            return service.equals(resolveInfo.services.get(0).getComponent());
        } else {
            // More than one service, not the default
            return false;
        }
    }

    /**
     * Resolves a conflict between multiple services handling the same
     * AIDs. Note that the AID itself is not an input to the decision
     * process - the algorithm just looks at the competing services
     * and what preferences the user has indicated. In short, it works like
     * this:
     *
     * 1) If there is a preferred foreground service, that service wins
     * 2) Else, if there is a preferred payment service, that service wins
     * 3) Else, if there is no winner, and all conflicting services will be
     *    in the list of resolved services.
     */
     AidResolveInfo resolveAidConflictLocked(Collection<ServiceAidInfo> conflictingServices,
                                             boolean makeSingleServiceDefault) {
        if (conflictingServices == null || conflictingServices.size() == 0) {
            Log.e(TAG, "resolveAidConflict: No services passed in.");
            return null;
        }
        AidResolveInfo resolveInfo = new AidResolveInfo();
        resolveInfo.category = CardEmulation.CATEGORY_OTHER;

        NfcApduServiceInfo matchedForeground = null;
        NfcApduServiceInfo matchedPayment = null;
        for (ServiceAidInfo serviceAidInfo : conflictingServices) {
            boolean serviceClaimsPaymentAid =
                    CardEmulation.CATEGORY_PAYMENT.equals(serviceAidInfo.category);
            if (serviceAidInfo.service.getComponent().equals(mPreferredForegroundService)) {
                resolveInfo.services.add(serviceAidInfo.service);
                if (serviceClaimsPaymentAid) {
                    resolveInfo.category = CardEmulation.CATEGORY_PAYMENT;
                }
                matchedForeground = serviceAidInfo.service;
            } else if (serviceAidInfo.service.getComponent().equals(mPreferredPaymentService) &&
                    serviceClaimsPaymentAid) {
                resolveInfo.services.add(serviceAidInfo.service);
                resolveInfo.category = CardEmulation.CATEGORY_PAYMENT;
                matchedPayment = serviceAidInfo.service;
            } else {
                if (serviceClaimsPaymentAid) {
                    // If this service claims it's a payment AID, don't route it,
                    // because it's not the default. Otherwise, add it to the list
                    // but not as default.
                    if (DBG) Log.d(TAG, "resolveAidLocked: (Ignoring handling service " +
                            serviceAidInfo.service.getComponent() +
                            " because it's not the payment default.)");
                } else {
                    resolveInfo.services.add(serviceAidInfo.service);
                }
            }
        }
        if (matchedForeground != null) {
            // 1st priority: if the foreground app prefers a service,
            // and that service asks for the AID, that service gets it
            if (DBG) Log.d(TAG, "resolveAidLocked: DECISION: routing to foreground preferred " +
                    matchedForeground);
            resolveInfo.defaultService = matchedForeground;
        } else if (matchedPayment != null) {
            // 2nd priority: if there is a preferred payment service,
            // and that service claims this as a payment AID, that service gets it
            if (DBG) Log.d(TAG, "resolveAidLocked: DECISION: routing to payment default " +
                    "default " + matchedPayment);
            resolveInfo.defaultService = matchedPayment;
        } else {
            if (resolveInfo.services.size() == 1 && makeSingleServiceDefault) {
                if (DBG) Log.d(TAG, "resolveAidLocked: DECISION: making single handling service " +
                        resolveInfo.services.get(0).getComponent() + " default.");
                resolveInfo.defaultService = resolveInfo.services.get(0);
            } else {
                // Nothing to do, all services already in list
                if (DBG) Log.d(TAG, "resolveAidLocked: DECISION: routing to all matching services");
            }
        }
        return resolveInfo;
    }

    class DefaultServiceInfo {
        ServiceAidInfo paymentDefault;
        ServiceAidInfo foregroundDefault;
    }

    DefaultServiceInfo findDefaultServices(ArrayList<ServiceAidInfo> serviceAidInfos) {
        DefaultServiceInfo defaultServiceInfo = new DefaultServiceInfo();

        for (ServiceAidInfo serviceAidInfo : serviceAidInfos) {
            boolean serviceClaimsPaymentAid =
                    CardEmulation.CATEGORY_PAYMENT.equals(serviceAidInfo.category);
            if (serviceAidInfo.service.getComponent().equals(mPreferredForegroundService)) {
                defaultServiceInfo.foregroundDefault = serviceAidInfo;
            } else if (serviceAidInfo.service.getComponent().equals(mPreferredPaymentService) &&
                    serviceClaimsPaymentAid) {
                defaultServiceInfo.paymentDefault = serviceAidInfo;
            }
        }
        return defaultServiceInfo;
    }

    AidResolveInfo resolveAidConflictLocked(ArrayList<ServiceAidInfo> aidServices,
                                                  ArrayList<ServiceAidInfo> conflictingServices) {
        // Find defaults among the root AID services themselves
        DefaultServiceInfo aidDefaultInfo = findDefaultServices(aidServices);

        // Find any defaults among the children
        DefaultServiceInfo conflictingDefaultInfo = findDefaultServices(conflictingServices);
        AidResolveInfo resolveinfo;
        // Three conditions under which the root AID gets to be the default
        // 1. A service registering the root AID is the current foreground preferred
        // 2. A service registering the root AID is the current tap & pay default AND
        //    no child is the current foreground preferred
        // 3. There is only one service for the root AID, and there are no children
        if (aidDefaultInfo.foregroundDefault != null) {
            if (DBG) Log.d(TAG, "Prefix AID service " +
                    aidDefaultInfo.foregroundDefault.service.getComponent() + " has foreground" +
                    " preference, ignoring conflicting AIDs.");
            // Foreground default trumps any conflicting services, treat as normal AID conflict
            // and ignore children
            resolveinfo = resolveAidConflictLocked(aidServices, true);
            //If the AID is subsetAID check for prefix in same service.
            if (isSubset(aidServices.get(0).aid)) {
                resolveinfo.prefixInfo = findPrefixConflictForSubsetAid(aidServices.get(0).aid ,
                        new ArrayList<NfcApduServiceInfo>(){{add(resolveinfo.defaultService);}},true);
            }
             return resolveinfo;
        } else if (aidDefaultInfo.paymentDefault != null) {
            // Check if any of the conflicting services is foreground default
            if (conflictingDefaultInfo.foregroundDefault != null) {
                // Conflicting AID registration is in foreground, trumps prefix tap&pay default
                if (DBG) Log.d(TAG, "One of the conflicting AID registrations is foreground " +
                        "preferred, ignoring prefix.");
                return EMPTY_RESOLVE_INFO;
            } else {
                // Prefix service is tap&pay default, treat as normal AID conflict for just prefix
                if (DBG) Log.d(TAG, "Prefix AID service " +
                    aidDefaultInfo.paymentDefault.service.getComponent() + " is payment" +
                        " default, ignoring conflicting AIDs.");
                resolveinfo = resolveAidConflictLocked(aidServices, true);
                //If the AID is subsetAID check for prefix in same service.
                if (isSubset(aidServices.get(0).aid)) {
                    resolveinfo.prefixInfo = findPrefixConflictForSubsetAid(aidServices.get(0).aid ,
                            new ArrayList<NfcApduServiceInfo>(){{add(resolveinfo.defaultService);}},true);
                }
                return resolveinfo;
            }
        } else {
            if (conflictingDefaultInfo.foregroundDefault != null ||
                    conflictingDefaultInfo.paymentDefault != null) {
                if (DBG) Log.d(TAG, "One of the conflicting AID registrations is either payment " +
                        "default or foreground preferred, ignoring prefix.");
                return EMPTY_RESOLVE_INFO;
            } else {
                // No children that are preferred; add all services of the root
                // make single service default if no children are present
                if (DBG) Log.d(TAG, "No service has preference, adding all.");
                resolveinfo = resolveAidConflictLocked(aidServices, conflictingServices.isEmpty());
                //If the AID is subsetAID check for conflicting prefix in all
                //conflciting services and root services.
                if (isSubset(aidServices.get(0).aid)) {
                    ArrayList <NfcApduServiceInfo> apduServiceList = new  ArrayList <NfcApduServiceInfo>();
                    for (ServiceAidInfo serviceInfo : conflictingServices)
                        apduServiceList.add(serviceInfo.service);
                    for (ServiceAidInfo serviceInfo : aidServices)
                        apduServiceList.add(serviceInfo.service);
                    resolveinfo.prefixInfo =
                         findPrefixConflictForSubsetAid(aidServices.get(0).aid ,apduServiceList,false);
                }
                return resolveinfo;
            }
        }
    }

    void generateServiceMapLocked(List<NfcApduServiceInfo> services) {
        // Easiest is to just build the entire tree again
        mAidServices.clear();
        for (NfcApduServiceInfo service : services) {
            if (DBG) Log.d(TAG, "generateServiceMap component: " + service.getComponent());
            List<String> prefixAids = service.getPrefixAids();
            List<String> subSetAids = service.getSubsetAids();

            for (String aid : service.getAids()) {
                if (!CardEmulation.isValidAid(aid)) {
                    if (DBG) Log.e(TAG, "Aid " + aid + " is not valid.");
                    continue;
                }
                if (aid.endsWith("*") && !supportsAidPrefixRegistration()) {
                   if (DBG) Log.e(TAG, "Prefix AID " + aid + " ignored on device that doesn't support it.");
                    continue;
                } else if (supportsAidPrefixRegistration() && prefixAids.size() > 0 && isExact(aid)) {
                    // Check if we already have an overlapping prefix registered for this AID
                    boolean foundPrefix = false;
                    for (String prefixAid : prefixAids) {
                        String prefix = prefixAid.substring(0, prefixAid.length() - 1);
                        if (aid.startsWith(prefix)) {
                            if (DBG) Log.e(TAG, "Ignoring exact AID " + aid + " because prefix AID " + prefixAid +
                                    " is already registered");
                            foundPrefix = true;
                            break;
                        }
                    }
                    if (foundPrefix) {
                        continue;
                    }
                } else if (aid.endsWith("#") && !supportsAidSubsetRegistration()) {
                    if (DBG) Log.e(TAG, "Subset AID " + aid + " ignored on device that doesn't support it.");
                    continue;
                } else if (supportsAidSubsetRegistration() && subSetAids.size() > 0 && isExact(aid)) {
                    // Check if we already have an overlapping subset registered for this AID
                    boolean foundSubset = false;
                    for (String subsetAid : subSetAids) {
                        String plainSubset = subsetAid.substring(0, subsetAid.length() - 1);
                        if (plainSubset.startsWith(aid)) {
                            if (DBG) Log.e(TAG, "Ignoring exact AID " + aid + " because subset AID " + plainSubset +
                                    " is already registered");
                            foundSubset = true;
                            break;
                        }
                    }
                    if (foundSubset) {
                        continue;
                    }
                }

                ServiceAidInfo serviceAidInfo = new ServiceAidInfo();
                serviceAidInfo.aid = aid.toUpperCase();
                serviceAidInfo.service = service;
                serviceAidInfo.category = service.getCategoryForAid(aid);
                if( (serviceAidInfo.category.equals(CardEmulation.CATEGORY_OTHER)) &&
                    ((service.getServiceState(CardEmulation.CATEGORY_OTHER) == NfcConstants.SERVICE_STATE_DISABLED) ||
                     (service.getServiceState(CardEmulation.CATEGORY_OTHER) == NfcConstants.SERVICE_STATE_DISABLING))){
                    /*Do not include the services which are already disabled Or services which are disabled by user recently
                     * for the current commit to routing table*/
                    if (DBG) Log.e(TAG, "ignoring other category aid because service category is disabled");
                    continue;
                }
                if (mAidServices.containsKey(serviceAidInfo.aid)) {
                    final ArrayList<ServiceAidInfo> serviceAidInfos =
                            mAidServices.get(serviceAidInfo.aid);
                    serviceAidInfos.add(serviceAidInfo);
                } else {
                    final ArrayList<ServiceAidInfo> serviceAidInfos =
                            new ArrayList<ServiceAidInfo>();
                    serviceAidInfos.add(serviceAidInfo);
                    mAidServices.put(serviceAidInfo.aid, serviceAidInfos);
                }
            }
        }
    }

    static boolean isExact(String aid) {
        return (!((aid.endsWith("*") || (aid.endsWith("#")))));
    }

    static boolean isPrefix(String aid) {
        if (aid == null) {
            return false;
        }
        return aid.endsWith("*");
    }

    static boolean isSubset(String aid) {
        if (aid == null) {
            return false;
        }
        return aid.endsWith("#");
    }

    final class ReslovedPrefixConflictAid {
        String prefixAid = null;
        boolean matchingSubset = false;
    }

    final class AidConflicts {
        NavigableMap<String, ArrayList<ServiceAidInfo>> conflictMap;
        final ArrayList<ServiceAidInfo> services = new ArrayList<ServiceAidInfo>();
        final HashSet<String> aids = new HashSet<String>();
    }

    ReslovedPrefixConflictAid findPrefixConflictForSubsetAid(String subsetAid ,
            ArrayList<NfcApduServiceInfo> prefixServices, boolean priorityRootAid){
        ArrayList<String> prefixAids = new ArrayList<String>();
        String minPrefix = null;
        //This functions checks whether there is a prefix AID matching to subset AID
        //Because both the subset AID and matching smaller perfix are to be added to routing table.
        //1.Finds the prefix matching AID in the services sent.
        //2.Find the smallest prefix among matching prefix and add it only if it is not same as susbet AID.
        //3..If the subset AID and prefix AID are same add only one AID with both prefix , subset bits set.
        // Cut off "#"
        String plainSubsetAid = subsetAid.substring(0, subsetAid.length() - 1);
        for (NfcApduServiceInfo service : prefixServices) {
            for (String prefixAid : service.getPrefixAids()) {
                // Cut off "#"
                String plainPrefix= prefixAid.substring(0, prefixAid.length() - 1);
                if( plainSubsetAid.startsWith(plainPrefix)) {
                    if (priorityRootAid) {
                       if (CardEmulation.CATEGORY_PAYMENT.equals(service.getCategoryForAid(prefixAid)) ||
                               (service.getComponent().equals(mPreferredForegroundService)))
                           prefixAids.add(prefixAid);
                    } else {
                        prefixAids.add(prefixAid);
                    }
                }
            }
        }
        if (prefixAids.size() > 0)
            minPrefix = Collections.min(prefixAids);
        ReslovedPrefixConflictAid resolvedPrefix = new ReslovedPrefixConflictAid();
        resolvedPrefix.prefixAid = minPrefix;
        if ((minPrefix != null ) &&
                plainSubsetAid.equalsIgnoreCase(minPrefix.substring(0, minPrefix.length() - 1)))
            resolvedPrefix.matchingSubset = true;
        return resolvedPrefix;
    }

    AidConflicts findConflictsForPrefixLocked(String prefixAid) {
        AidConflicts prefixConflicts = new AidConflicts();
        String plainAid = prefixAid.substring(0, prefixAid.length() - 1); // Cut off "*"
        String lastAidWithPrefix = String.format("%-32s", plainAid).replace(' ', 'F');
        if (DBG) Log.d(TAG, "Finding AIDs in range [" + plainAid + " - " +
                lastAidWithPrefix + "]");
        prefixConflicts.conflictMap =
                mAidServices.subMap(plainAid, true, lastAidWithPrefix, true);
        for (Map.Entry<String, ArrayList<ServiceAidInfo>> entry :
                prefixConflicts.conflictMap.entrySet()) {
            if (!entry.getKey().equalsIgnoreCase(prefixAid)) {
                if (DBG)
                    Log.d(TAG, "AID " + entry.getKey() + " conflicts with prefix; " +
                            " adding handling services for conflict resolution.");
                prefixConflicts.services.addAll(entry.getValue());
                prefixConflicts.aids.add(entry.getKey());
            }
        }
        return prefixConflicts;
    }

    AidConflicts findConflictsForSubsetAidLocked(String subsetAid) {
        AidConflicts subsetConflicts = new AidConflicts();
        // Cut off "@"
        String lastPlainAid = subsetAid.substring(0, subsetAid.length() - 1);
        // Cut off "@"
        String plainSubsetAid = subsetAid.substring(0, subsetAid.length() - 1);
        String firstAid = subsetAid.substring(0, 10);
        if (DBG) Log.d(TAG, "Finding AIDs in range [" + firstAid + " - " +
            lastPlainAid + "]");
        subsetConflicts.conflictMap = new TreeMap();
        for (Map.Entry<String, ArrayList<ServiceAidInfo>> entry :
            mAidServices.entrySet()) {
            String aid = entry.getKey();
            String plainAid = aid;
            if (isSubset(aid) || isPrefix(aid))
                plainAid = aid.substring(0, aid.length() - 1);
            if (plainSubsetAid.startsWith(plainAid))
                subsetConflicts.conflictMap.put(entry.getKey(),entry.getValue());
        }
        for (Map.Entry<String, ArrayList<ServiceAidInfo>> entry :
            subsetConflicts.conflictMap.entrySet()) {
            if (!entry.getKey().equalsIgnoreCase(subsetAid)) {
                if (DBG)
                    Log.d(TAG, "AID " + entry.getKey() + " conflicts with subset AID; " +
                            " adding handling services for conflict resolution.");
                subsetConflicts.services.addAll(entry.getValue());
                subsetConflicts.aids.add(entry.getKey());
            }
        }
        return subsetConflicts;
    }

    void generateAidCacheLocked() {
        mAidCache.clear();
        // Get all exact and prefix AIDs in an ordered list
        final TreeMap<String, AidResolveInfo> aidCache = new TreeMap<String, AidResolveInfo>();

        //aidCache is temproary cache for geenrating the first prefix based lookup table.
        PriorityQueue<String> aidsToResolve = new PriorityQueue<String>(mAidServices.keySet());
        aidCache.clear();
        while (!aidsToResolve.isEmpty()) {
            final ArrayList<String> resolvedAids = new ArrayList<String>();

            String aidToResolve = aidsToResolve.peek();
            // Because of the lexicographical ordering, all following AIDs either start with the
            // same bytes and are longer, or start with different bytes.

            // A special case is if another service registered the same AID as a prefix, in
            // which case we want to start with that AID, since it conflicts with this one
            // All exact and suffix and prefix AID must be checked for conflicting cases
            if (aidsToResolve.contains(aidToResolve + "*")) {
                aidToResolve = aidToResolve + "*";
            }
            if (DBG) Log.d(TAG, "generateAidCacheLocked: starting with aid " + aidToResolve);

            if (isPrefix(aidToResolve)) {
                // This AID itself is a prefix; let's consider this prefix as the "root",
                // and all conflicting AIDs as its children.
                // For example, if "A000000003*" is the prefix root,
                // "A000000003", "A00000000301*", "A0000000030102" are all conflicting children AIDs
                final ArrayList<ServiceAidInfo> prefixServices = new ArrayList<ServiceAidInfo>(
                        mAidServices.get(aidToResolve));

                // Find all conflicting children services
                AidConflicts prefixConflicts = findConflictsForPrefixLocked(aidToResolve);

                // Resolve conflicts
                AidResolveInfo resolveInfo = resolveAidConflictLocked(prefixServices,
                        prefixConflicts.services);
                aidCache.put(aidToResolve, resolveInfo);
                resolvedAids.add(aidToResolve);
                if (resolveInfo.defaultService != null) {
                    // This prefix is the default; therefore, AIDs of all conflicting children
                    // will no longer be evaluated.
                    resolvedAids.addAll(prefixConflicts.aids);
                    for (String aid : resolveInfo.defaultService.getSubsetAids()) {
                        if (prefixConflicts.aids.contains(aid)) {
                            if ((CardEmulation.CATEGORY_PAYMENT.equals(resolveInfo.defaultService.getCategoryForAid(aid))) ||
                                    (resolveInfo.defaultService.getComponent().equals(mPreferredForegroundService))) {
                                AidResolveInfo childResolveInfo = resolveAidConflictLocked(mAidServices.get(aid), false);
                                aidCache.put(aid,childResolveInfo);
                                if (DBG) Log.d(TAG, "AID " + aid+ " shared with prefix; " +
                                                "adding subset .");
                             }
                        }
                   }
                } else if (resolveInfo.services.size() > 0) {
                    // This means we don't have a default for this prefix and all its
                    // conflicting children. So, for all conflicting AIDs, just add
                    // all handling services without setting a default
                    boolean foundChildService = false;
                    for (Map.Entry<String, ArrayList<ServiceAidInfo>> entry :
                            prefixConflicts.conflictMap.entrySet()) {
                        if (!entry.getKey().equalsIgnoreCase(aidToResolve)) {
                            if (DBG)
                                Log.d(TAG, "AID " + entry.getKey() + " shared with prefix; " +
                                        " adding all handling services.");
                            AidResolveInfo childResolveInfo = resolveAidConflictLocked(
                                    entry.getValue(), false);
                            // Special case: in this case all children AIDs must be routed to the
                            // host, so we can ask the user which service is preferred.
                            // Since these are all "children" of the prefix, they don't need
                            // to be routed, since the prefix will already get routed to the host
                            childResolveInfo.mustRoute = false;
                            aidCache.put(entry.getKey(),childResolveInfo);
                            resolvedAids.add(entry.getKey());
                            foundChildService |= !childResolveInfo.services.isEmpty();
                        }
                    }
                    // Special case: if in the end we didn't add any children services,
                    // and the prefix has only one service, make that default
                    if (!foundChildService && resolveInfo.services.size() == 1) {
                        resolveInfo.defaultService = resolveInfo.services.get(0);
                    }
                } else {
                    // This prefix is not handled at all; we will evaluate
                    // the children separately in next passes.
                }
            } else {
                // Exact AID and no other conflicting AID registrations present
                // This is true because aidsToResolve is lexicographically ordered, and
                // so by necessity all other AIDs are different than this AID or longer.
                if (DBG) Log.d(TAG, "Exact AID, resolving.");
                final ArrayList<ServiceAidInfo> conflictingServiceInfos =
                        new ArrayList<ServiceAidInfo>(mAidServices.get(aidToResolve));
                aidCache.put(aidToResolve, resolveAidConflictLocked(conflictingServiceInfos, true));
                resolvedAids.add(aidToResolve);
            }

            // Remove the AIDs we resolved from the list of AIDs to resolve
            if (DBG) Log.d(TAG, "AIDs: " + resolvedAids + " were resolved.");
            aidsToResolve.removeAll(resolvedAids);
            resolvedAids.clear();
        }
        PriorityQueue<String> reversedQueue = new PriorityQueue<String>(1, Collections.reverseOrder());
        reversedQueue.addAll(aidCache.keySet());
        while (!reversedQueue.isEmpty()) {
            final ArrayList<String> resolvedAids = new ArrayList<String>();

            String aidToResolve = reversedQueue.peek();
            if (isPrefix(aidToResolve)) {
                String matchingSubset = aidToResolve.substring(0,aidToResolve.length()-1 ) + "#";
                if (DBG) Log.d(TAG, "matching subset"+matchingSubset);
                if (reversedQueue.contains(matchingSubset))
                     aidToResolve = aidToResolve.substring(0,aidToResolve.length()-1) + "#";
            }
            if (isSubset(aidToResolve)) {
                if (DBG) Log.d(TAG, "subset resolving aidToResolve  "+aidToResolve);
                final ArrayList<ServiceAidInfo> subsetServices = new ArrayList<ServiceAidInfo>(
                        mAidServices.get(aidToResolve));

                // Find all conflicting children services
                AidConflicts aidConflicts = findConflictsForSubsetAidLocked(aidToResolve);

                // Resolve conflicts
                AidResolveInfo resolveInfo = resolveAidConflictLocked(subsetServices,
                        aidConflicts.services);
                mAidCache.put(aidToResolve, resolveInfo);
                resolvedAids.add(aidToResolve);
                if (resolveInfo.defaultService != null) {
                    // This subset is the default; therefore, AIDs of all conflicting children
                    // will no longer be evaluated.Check for any prefix matching in the same service
                    if (resolveInfo.prefixInfo != null && resolveInfo.prefixInfo.prefixAid != null &&
                            !resolveInfo.prefixInfo.matchingSubset) {
                        if (DBG)
                            Log.d(TAG, "AID default " + resolveInfo.prefixInfo.prefixAid +
                                    " prefix AID shared with dsubset root; " +
                                    " adding prefix aid");
                        AidResolveInfo childResolveInfo = resolveAidConflictLocked(
                        mAidServices.get(resolveInfo.prefixInfo.prefixAid), false);
                        mAidCache.put(resolveInfo.prefixInfo.prefixAid, childResolveInfo);
                    }
                    resolvedAids.addAll(aidConflicts.aids);
                } else if (resolveInfo.services.size() > 0) {
                    // This means we don't have a default for this subset and all its
                    // conflicting children. So, for all conflicting AIDs, just add
                    // all handling services without setting a default
                    boolean foundChildService = false;
                    for (Map.Entry<String, ArrayList<ServiceAidInfo>> entry :
                        aidConflicts.conflictMap.entrySet()) {
                        // We need to add shortest prefix among them.
                        if (!entry.getKey().equalsIgnoreCase(aidToResolve)) {
                            if (DBG)
                                Log.d(TAG, "AID " + entry.getKey() + " shared with subset root; " +
                                        " adding all handling services.");
                            AidResolveInfo childResolveInfo = resolveAidConflictLocked(
                                entry.getValue(), false);
                            // Special case: in this case all children AIDs must be routed to the
                            // host, so we can ask the user which service is preferred.
                            // Since these are all "children" of the subset, they don't need
                            // to be routed, since the subset will already get routed to the host
                            childResolveInfo.mustRoute = false;
                            mAidCache.put(entry.getKey(),childResolveInfo);
                            resolvedAids.add(entry.getKey());
                            foundChildService |= !childResolveInfo.services.isEmpty();
                        }
                    }
                    if(resolveInfo.prefixInfo != null &&
                            resolveInfo.prefixInfo.prefixAid != null &&
                            !resolveInfo.prefixInfo.matchingSubset) {
                        AidResolveInfo childResolveInfo = resolveAidConflictLocked(
                        mAidServices.get(resolveInfo.prefixInfo.prefixAid), false);
                        mAidCache.put(resolveInfo.prefixInfo.prefixAid, childResolveInfo);
                        if (DBG)
                            Log.d(TAG, "AID " + resolveInfo.prefixInfo.prefixAid +
                                    " prefix AID shared with subset root; " +
                                    " adding prefix aid");
                    }
                    // Special case: if in the end we didn't add any children services,
                    // and the subset has only one service, make that default
                    if (!foundChildService && resolveInfo.services.size() == 1) {
                        resolveInfo.defaultService = resolveInfo.services.get(0);
                    }
                } else {
                    // This subset is not handled at all; we will evaluate
                    // the children separately in next passes.
                }
            } else {
                // Exact AID and no other conflicting AID registrations present. This is
                // true because reversedQueue is lexicographically ordered in revrese, and
                // so by necessity all other AIDs are different than this AID or shorter.
                if (DBG) Log.d(TAG, "Exact or Prefix AID."+aidToResolve);
                mAidCache.put(aidToResolve, aidCache.get(aidToResolve));
                resolvedAids.add(aidToResolve);
            }

            // Remove the AIDs we resolved from the list of AIDs to resolve
            if (DBG) Log.d(TAG, "AIDs: " + resolvedAids + " were resolved.");
            reversedQueue.removeAll(resolvedAids);
            resolvedAids.clear();
        }

        if (NfcService.getInstance().mIsRouteForced) {
            updateRoutingLocked(true);
        } else {
            updateRoutingLocked(false);
        }
    }

    void updateRoutingLocked(boolean force) {
        if (!mNfcEnabled) {
            if (DBG) Log.d(TAG, "Not updating routing table because NFC is off.");
            return;
        }
        final HashMap<String, AidRoutingManager.AidEntry> routingEntries = Maps.newHashMap();
        int mGsmaPwrState = NfcService.getInstance().getGsmaPwrState();
        boolean isNxpExtnEnabled = NfcService.getInstance().isNfcExtnsPresent();
        // For each AID, find interested services
        for (Map.Entry<String, AidResolveInfo> aidEntry:
                mAidCache.entrySet()) {
            String aid = aidEntry.getKey();
            AidResolveInfo resolveInfo = aidEntry.getValue();
            if (!resolveInfo.mustRoute) {
                if (DBG) Log.d(TAG, "Not routing AID " + aid + " on request.");
                continue;
            }
            AidRoutingManager.AidEntry aidType = mRoutingManager.new AidEntry();
            if (aid.endsWith("#")) {
                aidType.aidInfo |= AID_ROUTE_QUAL_SUBSET;
            }
            if(aid.endsWith("*") || (resolveInfo.prefixInfo != null &&
                    resolveInfo.prefixInfo.matchingSubset)) {
                aidType.aidInfo |= AID_ROUTE_QUAL_PREFIX;
            }
            if (resolveInfo.services.size() == 0) {
                // No interested services
            } else if (resolveInfo.defaultService != null) {
                // There is a default service set, route to where that service resides -
                // either on the host (HCE) or on an SE.
                NfcApduServiceInfo.ESeInfo seInfo = resolveInfo.defaultService.getSEInfo();
                aidType.isOnHost = resolveInfo.defaultService.isOnHost();
                if (!aidType.isOnHost) {
                    aidType.offHostSE =
                            resolveInfo.defaultService.getOffHostSecureElement();
                }
                int powerstate = seInfo.getPowerState() & POWER_STATE_ALL;
                int screenstate= 0;
                if(powerstate == 0x00) {
                    powerstate = (NfcService.getInstance().GetDefaultMifateCLTRouteEntry() & 0x3F);
                    if(mGsmaPwrState > 0)
                    {
                        if(aidType.isOnHost)
                        {
                            if(NfcService.getInstance().getNciVersion() ==
                                        NfcService.getInstance().NCI_VERSION_1_0){
                                powerstate = updateRoutePowerState(mGsmaPwrState & 0x39);
                            } else {
                                powerstate = (mGsmaPwrState & 0x39);
                            }
                        } else
                        {
                            if(NfcService.getInstance().getNciVersion() ==
                                           NfcService.getInstance().NCI_VERSION_1_0){
                                powerstate = updateRoutePowerState(mGsmaPwrState);
                            } else {
                                powerstate = (mGsmaPwrState);
                            }
                        }
                        if (DBG) Log.d(TAG," Setting GSMA power state"+ aid  + powerstate);
                    }
                }

                boolean isOnHost = resolveInfo.defaultService.isOnHost();
                if ((powerstate & POWER_STATE_SWITCH_ON) == POWER_STATE_SWITCH_ON )
                {
                  if(NfcService.getInstance().getNciVersion() ==
                                        NfcService.getInstance().NCI_VERSION_1_0){
                      screenstate |= updateRoutePowerState(SCREEN_STATE_ON_LOCKED);
                  } else {
                      screenstate |= SCREEN_STATE_ON_LOCKED;
                  }
                  if (!isOnHost || isNxpExtnEnabled) {
                    if (DBG) Log.d(TAG," set screen off enable for " + aid);
                    if(NfcService.getInstance().getNciVersion() ==
                                        NfcService.getInstance().NCI_VERSION_1_0){
                        screenstate |= updateRoutePowerState(SCREEN_STATE_OFF_UNLOCKED) |
                              updateRoutePowerState(SCREEN_STATE_OFF_LOCKED);
                    } else{
                        screenstate |= SCREEN_STATE_OFF_UNLOCKED | SCREEN_STATE_OFF_LOCKED;
                    }
                  }
                  if(mGsmaPwrState == 0x00)
                    powerstate |= screenstate;
                }

               int route = isOnHost ? 0 : seInfo.getSeId();
               if (DBG) Log.d(TAG," AID power state "+ aid  + " "+ powerstate  +" route "+route);
               aidType.route = route;
               if(NfcService.getInstance().getNciVersion() ==
                                        NfcService.getInstance().NCI_VERSION_1_0){
                   aidType.powerstate = powerstate;
               } else {
                   aidType.powerstate = updateRoutePowerState(powerstate);
               }
               routingEntries.put(aid, aidType);
            } else if (resolveInfo.services.size() == 1) {
                // Only one service, but not the default, must route to host
                // to ask the user to choose one.
                aidType.isOnHost = true;
                if(NfcService.getInstance().getNciVersion() ==
                                        NfcService.getInstance().NCI_VERSION_1_0){
                    aidType.powerstate = updateRoutePowerState(POWER_STATE_SWITCH_ON) |
                          updateRoutePowerState(SCREEN_STATE_ON_LOCKED);
                } else {
                    aidType.powerstate = POWER_STATE_SWITCH_ON | SCREEN_STATE_ON_LOCKED;
                }

                if(isNxpExtnEnabled) {
                    aidType.powerstate |= SCREEN_STATE_OFF_UNLOCKED | SCREEN_STATE_OFF_LOCKED;
                  }

                if (DBG) Log.d(TAG," AID power state 2 "+ aid  +" "+aidType.powerstate);
                if(mGsmaPwrState > 0)
                {
                    aidType.powerstate = (mGsmaPwrState & 0x39);
                    if (DBG) Log.d(TAG," Setting GSMA power state"+ aid  + " " +aidType.powerstate);
                }
                if(NfcService.getInstance().getNciVersion() >=
                                        NfcService.getInstance().NCI_VERSION_2_0){
                    aidType.powerstate = updateRoutePowerState(aidType.powerstate);
                }
                routingEntries.put(aid, aidType);
            } else if (resolveInfo.services.size() > 1) {
                // Multiple services if all the services are routing to same
                // offhost then the service should be routed to off host.
                boolean onHost = false;
                String offHostSE = null;
                for (NfcApduServiceInfo service : resolveInfo.services) {
                    // In case there is at least one service which routes to host
                    // Route it to host for user to select which service to use
                    onHost |= service.isOnHost();
                    if (!onHost) {
                        if (offHostSE == null) {
                            offHostSE = service.getOffHostSecureElement();
                        } else if (!offHostSE.equals(
                                service.getOffHostSecureElement())) {
                            // There are registerations to different SEs, route this
                            // to host and have user choose a service for this AID
                            offHostSE = null;
                            onHost = true;
                            break;
                        }
                    }
                }
                aidType.isOnHost = onHost;
                aidType.offHostSE = onHost ? null : offHostSE;
                if(NfcService.getInstance().getNciVersion() ==
                                        NfcService.getInstance().NCI_VERSION_1_0){
                    aidType.powerstate = updateRoutePowerState(POWER_STATE_SWITCH_ON) |
                          updateRoutePowerState(SCREEN_STATE_ON_LOCKED);
                } else {
                    aidType.powerstate = POWER_STATE_SWITCH_ON | SCREEN_STATE_ON_LOCKED;
                }

                if(isNxpExtnEnabled) {
                    aidType.powerstate |= SCREEN_STATE_OFF_UNLOCKED | SCREEN_STATE_OFF_LOCKED;
                }

                if(mGsmaPwrState > 0)
                {
                    if(NfcService.getInstance().getNciVersion() ==
                                        NfcService.getInstance().NCI_VERSION_1_0){
                        aidType.powerstate = updateRoutePowerState(mGsmaPwrState & 0x39);
                    } else {
                        aidType.powerstate = (mGsmaPwrState & 0x39);
                    }
                    if (DBG) Log.d(TAG," Setting GSMA power state"+ aid  + " " +aidType.powerstate);
                }
                if(NfcService.getInstance().getNciVersion() >=
                                        NfcService.getInstance().NCI_VERSION_2_0){
                    aidType.powerstate = updateRoutePowerState(aidType.powerstate);
                }
                if (DBG) Log.d(TAG," AID power state 3 "+ aid  + aidType.powerstate);
                routingEntries.put(aid, aidType);
            }
        }
        mRoutingManager.configureRouting(routingEntries, force);
    }

    public void onServicesUpdated(int userId, List<NfcApduServiceInfo> services) {
        if (DBG) Log.d(TAG, "onServicesUpdated");
        synchronized (mLock) {
            if (ActivityManager.getCurrentUser() == userId) {
                // Rebuild our internal data-structures
                generateServiceMapLocked(services);
                generateAidCacheLocked();
            } else {
                if (DBG) Log.d(TAG, "Ignoring update because it's not for the current user.");
            }
        }
    }

    public void onPreferredPaymentServiceChanged(ComponentName service) {
        if (DBG) Log.d(TAG, "Preferred payment service changed.");
       synchronized (mLock) {
           mPreferredPaymentService = service;
           generateAidCacheLocked();
       }
    }

    public void onPreferredForegroundServiceChanged(ComponentName service) {
        if (DBG) Log.d(TAG, "Preferred foreground service changed.");
        synchronized (mLock) {
            mPreferredForegroundService = service;
            generateAidCacheLocked();
        }
    }

    public void onRoutingTableChanged() {
      if (DBG)
        Log.d(TAG, "onRoutingTableChanged");
      synchronized (mLock) {
        generateAidCacheLocked();
      }
    }

    public ComponentName getPreferredService() {
        if (mPreferredForegroundService != null) {
            // return current foreground service
            return mPreferredForegroundService;
        } else {
            // return current preferred service
            return mPreferredPaymentService;
        }
    }

    public void onNfcDisabled() {
        synchronized (mLock) {
            mNfcEnabled = false;
        }
        mRoutingManager.onNfccRoutingTableCleared();
    }

    public void onNfcEnabled() {
        synchronized (mLock) {
            mNfcEnabled = true;
            updateRoutingLocked(false);
        }
    }

    public void onSecureNfcToggled() {
        synchronized (mLock) {
            updateRoutingLocked(true);
        }
    }

    String dumpEntry(Map.Entry<String, AidResolveInfo> entry) {
        StringBuilder sb = new StringBuilder();
        String category = entry.getValue().category;
        NfcApduServiceInfo defaultServiceInfo = entry.getValue().defaultService;
        sb.append("    \"" + entry.getKey() + "\" (category: " + category + ")\n");
        ComponentName defaultComponent = defaultServiceInfo != null ?
                defaultServiceInfo.getComponent() : null;

        for (NfcApduServiceInfo serviceInfo : entry.getValue().services) {
            sb.append("        ");
            if (serviceInfo.getComponent().equals(defaultComponent)) {
                sb.append("*DEFAULT* ");
            }
            sb.append(serviceInfo.getComponent() +
                    " (Description: " + serviceInfo.getDescription() + ")\n");
        }
        return sb.toString();
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("    AID cache entries: ");
        for (Map.Entry<String, AidResolveInfo> entry : mAidCache.entrySet()) {
            pw.println(dumpEntry(entry));
        }
        pw.println("    Service preferred by foreground app: " + mPreferredForegroundService);
        pw.println("    Preferred payment service: " + mPreferredPaymentService);
        pw.println("");
        mRoutingManager.dump(fd, pw, args);
        pw.println("");
    }

    int updateRoutePowerState(int inputPwr) {
      final int PROP_SCRN_ON_UNLOCKED = 0x20;
      final int PROP_SCRN_OFF = 0x08;
      Log.d(TAG, "updateRoutePowerState inputPwr "+ inputPwr);

      /*If extns service present mapping proprietary pwr state
       * to NCI2.0 pwr state*/
      if((NfcService.getInstance().isNfcExtnsPresent()) &&
              (inputPwr != SCREEN_STATE_INVALID)) {
        int tempPwrState = inputPwr & SCREEN_STATE_DEFAULT_MASK;

        if((inputPwr & POWER_STATE_SWITCH_ON) == POWER_STATE_SWITCH_ON) {
          /*Mapping SWITCH_ON(0x01) to PROP_SCRN_ON_UNLOCKED(0x20)*/
          tempPwrState |= PROP_SCRN_ON_UNLOCKED;
        }
        if((inputPwr & SCREEN_STATE_OFF_UNLOCKED) == SCREEN_STATE_OFF_UNLOCKED ||
                (inputPwr & SCREEN_STATE_OFF_LOCKED) == SCREEN_STATE_OFF_LOCKED) {
          /*Mapping SCREEN_STATE_OFF_UNLOCKED(0x08) or SCREEN_STATE_OFF_LOCKED(0x20)
           * to PROP_SCRN_OFF(0x08)*/
          tempPwrState |= PROP_SCRN_OFF;
        }
        inputPwr = tempPwrState;
      }
      Log.d(TAG, "updateRoutePowerState outputPwr "+ inputPwr);
      return inputPwr;
    }
}
