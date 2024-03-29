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

package com.android.server.telecom;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.IBluetoothHeadsetPhone;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.Bundle;
import android.os.RemoteException;
import android.telecom.Connection;
import android.telecom.Log;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.VideoProfile;
import android.telephony.PhoneNumberUtils;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.os.UserHandle;

import android.telecom.TelecomManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.PhoneConstants;
import com.android.server.telecom.CallsManager.CallsManagerListener;

import java.lang.NumberFormatException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bluetooth headset manager for Telecom. This class shares the call state with the bluetooth device
 * and accepts call-related commands to perform on behalf of the BT device.
 */
public class BluetoothPhoneServiceImpl {

    public interface BluetoothPhoneServiceImplFactory {
        BluetoothPhoneServiceImpl makeBluetoothPhoneServiceImpl(Context context,
                TelecomSystem.SyncRoot lock, CallsManager callsManager,
                PhoneAccountRegistrar phoneAccountRegistrar);
    }

    private static final String TAG = "BluetoothPhoneService";

    // match up with bthf_call_state_t of bt_hf.h
    private static final int CALL_STATE_ACTIVE = 0;
    private static final int CALL_STATE_HELD = 1;
    private static final int CALL_STATE_DIALING = 2;
    private static final int CALL_STATE_ALERTING = 3;
    private static final int CALL_STATE_INCOMING = 4;
    private static final int CALL_STATE_WAITING = 5;
    private static final int CALL_STATE_IDLE = 6;
    private static final int CALL_STATE_DISCONNECTED = 7;

    // match up with bthf_call_state_t of bt_hf.h
    // Terminate all held or set UDUB("busy") to a waiting call
    private static final int CHLD_TYPE_RELEASEHELD = 0;
    // Terminate all active calls and accepts a waiting/held call
    private static final int CHLD_TYPE_RELEASEACTIVE_ACCEPTHELD = 1;
    // Hold all active calls and accepts a waiting/held call
    private static final int CHLD_TYPE_HOLDACTIVE_ACCEPTHELD = 2;
    // Add all held calls to a conference
    private static final int CHLD_TYPE_ADDHELDTOCONF = 3;

    private int mNumActiveCalls = 0;
    private int mNumHeldCalls = 0;
    private int mNumChildrenOfActiveCall = 0;
    private int mBluetoothCallState = CALL_STATE_IDLE;
    private String mRingingAddress = null;
    private int mRingingAddressType = 0;
    private Call mOldHeldCall = null;
    private boolean mIsDisconnectedTonePlaying = false;
    private boolean isAnswercallInProgress = false;

    /**
     * Binder implementation of IBluetoothHeadsetPhone. Implements the command interface that the
     * bluetooth headset code uses to control call.
     */
    @VisibleForTesting
    public final IBluetoothHeadsetPhone.Stub mBinder = new IBluetoothHeadsetPhone.Stub() {
        @Override
        public boolean answerCall() throws RemoteException {
            synchronized (mLock) {
                enforceModifyPermission();
                Log.startSession("BPSI.aC");
                long token = Binder.clearCallingIdentity();
                try {
                    Log.i(TAG, "BT - answering call isAnswercallInProgress "
                              + isAnswercallInProgress );
                    Call call = mCallsManager.getFirstCallWithState(CallState.RINGING);
                    if (call != null) {
                        if (!isAnswercallInProgress) {
                            mCallsManager.answerCall(call, VideoProfile.STATE_AUDIO_ONLY);
                            Log.i(TAG, "Making isAnswercallInProgress to true");
                            isAnswercallInProgress = true;
                        }
                        return true;
                    }
                    return false;
                } finally {
                    Binder.restoreCallingIdentity(token);
                    Log.endSession();
                }

            }
        }

        @Override
        public boolean hangupCall() throws RemoteException {
            synchronized (mLock) {
                enforceModifyPermission();
                Log.startSession("BPSI.hC");
                long token = Binder.clearCallingIdentity();
                try {
                    Log.i(TAG, "BT - hanging up call");
                    Call call = mCallsManager.getForegroundCall();
                    if (call != null) {
                        mCallsManager.disconnectCall(call);
                        return true;
                    }
                    return false;
                } finally {
                    Binder.restoreCallingIdentity(token);
                    Log.endSession();
                }
            }
        }

        @Override
        public boolean sendDtmf(int dtmf) throws RemoteException {
            synchronized (mLock) {
                enforceModifyPermission();
                Log.startSession("BPSI.sD");
                long token = Binder.clearCallingIdentity();
                try {
                    Log.i(TAG, "BT - sendDtmf %c", Log.DEBUG ? dtmf : '.');
                    Call call = mCallsManager.getForegroundCall();
                    if (call != null) {
                        // TODO: Consider making this a queue instead of starting/stopping
                        // in quick succession.
                        mCallsManager.playDtmfTone(call, (char) dtmf);
                        mCallsManager.stopDtmfTone(call);
                        return true;
                    }
                    return false;
                } finally {
                    Binder.restoreCallingIdentity(token);
                    Log.endSession();
                }
            }
        }

        @Override
        public String getNetworkOperator() throws RemoteException {
            synchronized (mLock) {
                enforceModifyPermission();
                Log.startSession("BPSI.gNO");
                long token = Binder.clearCallingIdentity();
                try {
                    Log.i(TAG, "getNetworkOperator");
                    String label = null;
                    PhoneAccount account = getBestPhoneAccount();
                    if (account != null) {
                        PhoneAccountHandle ph = account.getAccountHandle();
                        if (ph != null) {
                            String subId = ph.getId();
                            int sub;
                            try {
                                sub = Integer.parseInt(subId);
                            } catch (NumberFormatException e){
                                Log.w(this, " NumberFormatException " + e);
                                sub = SubscriptionManager.getDefaultVoiceSubscriptionId();
                            }
                            label = TelephonyManager.from(mContext)
                                    .getNetworkOperatorName(sub);
                        } else {
                            Log.w(this, "Phone Account Handle is NULL");
                        }
                    } else {
                        // Finally, just get the network name from telephony.
                         label = TelephonyManager.from(mContext)
                                .getNetworkOperatorName();
                    }
                    return label;
                } finally {
                    Binder.restoreCallingIdentity(token);
                    Log.endSession();
                }
            }
        }

        @Override
        public String getSubscriberNumber() throws RemoteException {
            synchronized (mLock) {
                enforceModifyPermission();
                Log.startSession("BPSI.gSN");
                long token = Binder.clearCallingIdentity();
                try {
                    Log.i(TAG, "getSubscriberNumber");
                    String address = null;
                    PhoneAccount account = getBestPhoneAccount();
                    if (account != null) {
                        Uri addressUri = account.getAddress();
                        if (addressUri != null) {
                            address = addressUri.getSchemeSpecificPart();
                        }
                    }
                    if (TextUtils.isEmpty(address)) {
                        address = TelephonyManager.from(mContext).getLine1Number();
                        if (address == null) address = "";
                    }
                    return address;
                } finally {
                    Binder.restoreCallingIdentity(token);
                    Log.endSession();
                }
            }
        }

        @Override
        public boolean listCurrentCalls() throws RemoteException {
            synchronized (mLock) {
                enforceModifyPermission();
                Log.startSession("BPSI.lCC");
                long token = Binder.clearCallingIdentity();
                try {
                    // only log if it is after we recently updated the headset state or else it can
                    // clog the android log since this can be queried every second.
                    boolean logQuery = mHeadsetUpdatedRecently;
                    mHeadsetUpdatedRecently = false;

                    if (logQuery) {
                        Log.i(TAG, "listcurrentCalls");
                    }

                    sendListOfCalls(logQuery);
                    return true;
                } finally {
                    Binder.restoreCallingIdentity(token);
                    Log.endSession();
                }
            }
        }

        @Override
        public boolean queryPhoneState() throws RemoteException {
            synchronized (mLock) {
                enforceModifyPermission();
                Log.startSession("BPSI.qPS");
                long token = Binder.clearCallingIdentity();
                try {
                    Log.i(TAG, "queryPhoneState");
                    updateHeadsetWithCallState(true /* force */);
                    return true;
                } finally {
                    Binder.restoreCallingIdentity(token);
                    Log.endSession();
                }
            }
        }

       /**isHighDefCallInProgress
        * Returns true  if there is any Call is in Progress with High Definition
        *               quality
        *         false otherwise.
        */
        @Override
        public boolean isHighDefCallInProgress() {
            boolean isHighDef = false;
            Call ringingCall = mCallsManager.getRingingCall();
            Call dialingCall = mCallsManager.getOutgoingCall();
            Call activeCall = mCallsManager.getActiveCall();

            /* If its an incoming call we will have codec info in dialing state */
            if (ringingCall != null) {
                isHighDef = ringingCall.hasProperty(Connection.PROPERTY_HIGH_DEF_AUDIO);
            } else if (dialingCall != null) { /* CS dialing call has codec info in dialing state */
                Bundle extras = dialingCall.getExtras();
                if (extras != null) {
                    int phoneType = extras.getInt(
                        TelecomManager.EXTRA_CALL_TECHNOLOGY_TYPE);
                    if (phoneType == PhoneConstants.PHONE_TYPE_GSM
                        || phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                        isHighDef = dialingCall.hasProperty(Connection.PROPERTY_HIGH_DEF_AUDIO);
                    /* For IMS calls codec info is not present in dialing state */
                    } else if (phoneType == PhoneConstants.PHONE_TYPE_IMS
                        || phoneType == PhoneConstants.PHONE_TYPE_CDMA_LTE) {
                        isHighDef = true;
                    }
                }
            } else if (activeCall != null) {
                isHighDef = activeCall.hasProperty(Connection.PROPERTY_HIGH_DEF_AUDIO);
            }
            Log.i(TAG, "isHighDefCallInProgress: Call is High Def " + isHighDef);
            return isHighDef;
        }

        @Override
        public boolean processChld(int chld) throws RemoteException {
            synchronized (mLock) {
                enforceModifyPermission();
                Log.startSession("BPSI.pC");
                long token = Binder.clearCallingIdentity();
                try {
                    Log.i(TAG, "processChld %d", chld);
                    return BluetoothPhoneServiceImpl.this.processChld(chld);
                } finally {
                    Binder.restoreCallingIdentity(token);
                    Log.endSession();
                }
            }
        }

        @Override
        public void updateBtHandsfreeAfterRadioTechnologyChange() throws RemoteException {
            Log.d(TAG, "RAT change - deprecated");
            // deprecated
        }

        @Override
        public void cdmaSetSecondCallState(boolean state) throws RemoteException {
            Log.d(TAG, "cdma 1 - deprecated");
            // deprecated
        }

        @Override
        public void cdmaSwapSecondCallState() throws RemoteException {
            Log.d(TAG, "cdma 2 - deprecated");
            // deprecated
        }
    };

    /**
     * Listens to call changes from the CallsManager and calls into methods to update the bluetooth
     * headset with the new states.
     */
    @VisibleForTesting
    public CallsManagerListener mCallsManagerListener = new CallsManagerListenerBase() {
        @Override
        public void onCallAdded(Call call) {
            if (call.isExternalCall()) {
                return;
            }
            updateHeadsetWithCallState(false /* force */);
        }

        @Override
        public void onCallRemoved(Call call) {
            if (call.isExternalCall()) {
                return;
            }
            mClccIndexMap.remove(call);
            updateHeadsetWithCallState(false /* force */);
        }

        /**
         * Where a call which was external becomes a regular call, or a regular call becomes
         * external, treat as an add or remove, respectively.
         *
         * @param call The call.
         * @param isExternalCall {@code True} if the call became external, {@code false} otherwise.
         */
        @Override
        public void onExternalCallChanged(Call call, boolean isExternalCall) {
            if (isExternalCall) {
                onCallRemoved(call);
            } else {
                onCallAdded(call);
            }
        }

        @Override
        public void onCallStateChanged(Call call, int oldState, int newState) {
            if (call.isExternalCall()) {
                return;
            }

            if (oldState == CallState.RINGING && newState != oldState) {
                Log.i(TAG, "making flag isAnswercallInProgress to false");
                isAnswercallInProgress = false;

                /* When one active call, one RINGING call is present, AT+CHLD=1
                 * ends active call. RINGING call moves to ANSWERED state. This
                 * will make BT to update headset that no call is present and
                 * close SCO. SCO will not be created when call moves to ACTIVE
                 * state since SCO disconnection may not complete by then.
                 * Ignore ANSWERED update and update to BT only when call
                 * moves to ACTIVE state */
                if (newState == CallState.ANSWERED) {
                    Log.w(TAG, "ignoring call update from RINGING->ANSWERED state");
                    return;
                }
            }
            // If a call is being put on hold because of a new connecting call, ignore the
            // CONNECTING since the BT state update needs to send out the numHeld = 1 + dialing
            // state atomically.
            // When the call later transitions to DIALING/DISCONNECTED we will then send out the
            // aggregated update.
            if (oldState == CallState.ACTIVE && newState == CallState.ON_HOLD) {
                for (Call otherCall : mCallsManager.getCalls()) {
                    if (otherCall.getState() == CallState.CONNECTING) {
                        return;
                    }
                }
            }

            // To have an active call and another dialing at the same time is an invalid BT
            // state. We can assume that the active call will be automatically held which will
            // send another update at which point we will be in the right state.
            if (mCallsManager.getActiveCall() != null
                    && oldState == CallState.CONNECTING &&
                    (newState == CallState.DIALING || newState == CallState.PULLING)) {
                return;
            }
            updateHeadsetWithCallState(false /* force */);
        }

        @Override
        public void onIsConferencedChanged(Call call) {
            if (call.isExternalCall()) {
                return;
            }
            /*
             * Filter certain onIsConferencedChanged callbacks. Unfortunately this needs to be done
             * because conference change events are not atomic and multiple callbacks get fired
             * when two calls are conferenced together. This confuses updateHeadsetWithCallState
             * if it runs in the middle of two calls being conferenced and can cause spurious and
             * incorrect headset state updates. One of the scenarios is described below for CDMA
             * conference calls.
             *
             * 1) Call 1 and Call 2 are being merged into conference Call 3.
             * 2) Call 1 has its parent set to Call 3, but Call 2 does not have a parent yet.
             * 3) updateHeadsetWithCallState now thinks that there are two active calls (Call 2 and
             * Call 3) when there is actually only one active call (Call 3).
             */
            if (call.getParentCall() != null) {
                // If this call is newly conferenced, ignore the callback. We only care about the
                // one sent for the parent conference call.
                Log.d(this, "Ignoring onIsConferenceChanged from child call with new parent");
                return;
            }
            if (call.getChildCalls().size() == 1) {
                // If this is a parent call with only one child, ignore the callback as well since
                // the minimum number of child calls to start a conference call is 2. We expect
                // this to be called again when the parent call has another child call added.
                Log.d(this, "Ignoring onIsConferenceChanged from parent with only one child call");
                return;
            }
            updateHeadsetWithCallState(false /* force */);
        }

        @Override
        public void onDisconnectedTonePlaying(boolean isTonePlaying) {
            mIsDisconnectedTonePlaying = isTonePlaying;
            updateHeadsetWithCallState(false /* force */);
        }
    };

    /**
     * Listens to connections and disconnections of bluetooth headsets.  We need to save the current
     * bluetooth headset so that we know where to send call updates.
     */
    @VisibleForTesting
    public BluetoothProfile.ServiceListener mProfileListener =
            new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    synchronized (mLock) {
                        Log.w(TAG, "onServiceConnected: setting mBluetoothHeadset");
                        setBluetoothHeadset(new BluetoothHeadsetProxy((BluetoothHeadset) proxy));
                        updateHeadsetWithCallState(true /* force */);
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    synchronized (mLock) {
                        if (mBluetoothHeadset != null) {
                            mBluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET,
                                    (BluetoothProfile)(mBluetoothHeadset.getBluetoothHeadsetObj()));
                            Log.w(TAG, "onServiceDisconnected: setting mBluetoothHeadet to null");
                            mBluetoothHeadset = null;
                        }
                    }
                }
            };

    /**
     * Receives events for global state changes of the bluetooth adapter.
     */
    @VisibleForTesting
    public final BroadcastReceiver mBluetoothAdapterReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                int state = intent
                        .getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                Log.d(TAG, "Bluetooth Adapter state: %d", state);
                if (state == BluetoothAdapter.STATE_ON) {
                    try {
                        Log.w(TAG, "Calling get Profile proxy");
                        mBluetoothAdapter.getProfileProxy(context, mProfileListener,
                                                 BluetoothProfile.HEADSET);
                        Log.w(TAG, "Done Calling get Profile proxy");
                        mBinder.queryPhoneState();
                    } catch (RemoteException e) {
                        // Remote exception not expected
                    }
                } else if(state == BluetoothAdapter.STATE_OFF) {
                    if (mBluetoothHeadset != null) {
                        mBluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET,
                                        (BluetoothProfile)(mBluetoothHeadset.getBluetoothHeadsetObj()));
                        Log.w(TAG, "setting mBluetoothHeadet to null");
                        mBluetoothHeadset = null;
                    }
                }
            }
        }
    };

    private BluetoothAdapterProxy mBluetoothAdapter;
    private BluetoothHeadsetProxy mBluetoothHeadset;

    // A map from Calls to indexes used to identify calls for CLCC (C* List Current Calls).
    private Map<Call, Integer> mClccIndexMap = new HashMap<>();

    private boolean mHeadsetUpdatedRecently = false;

    private final Context mContext;
    private final TelecomSystem.SyncRoot mLock;
    private final CallsManager mCallsManager;
    private final PhoneAccountRegistrar mPhoneAccountRegistrar;

    public IBinder getBinder() {
        return mBinder;
    }

    public BluetoothPhoneServiceImpl(
            Context context,
            TelecomSystem.SyncRoot lock,
            CallsManager callsManager,
            BluetoothAdapterProxy bluetoothAdapter,
            PhoneAccountRegistrar phoneAccountRegistrar) {
        Log.d(this, "onCreate");

        mContext = context;
        mLock = lock;
        mCallsManager = callsManager;
        mPhoneAccountRegistrar = phoneAccountRegistrar;

        mBluetoothAdapter = bluetoothAdapter;
        if (mBluetoothAdapter == null) {
            Log.d(this, "BluetoothPhoneService shutting down, no BT Adapter found.");
            return;
        }
        mBluetoothAdapter.getProfileProxy(context, mProfileListener, BluetoothProfile.HEADSET);

        IntentFilter intentFilter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        context.registerReceiver(mBluetoothAdapterReceiver, intentFilter);

        mCallsManager.addListener(mCallsManagerListener);
        updateHeadsetWithCallState(false /* force */);
    }

    @VisibleForTesting
    public void setBluetoothHeadset(BluetoothHeadsetProxy bluetoothHeadset) {
        mBluetoothHeadset = bluetoothHeadset;
    }

    private boolean processChld(int chld) {
        Call activeCall = mCallsManager.getActiveCall();
        Call ringingCall = mCallsManager.getFirstCallWithState(CallState.RINGING);
        Call heldCall = mCallsManager.getHeldCall();

        // TODO: Keeping as Log.i for now.  Move to Log.d after L release if BT proves stable.
        Log.i(TAG, "Active: %s\nRinging: %s\nHeld: %s", activeCall, ringingCall, heldCall);

        if (chld == CHLD_TYPE_RELEASEHELD) {
            if (ringingCall != null) {
                mCallsManager.rejectCall(ringingCall, false, null);
                return true;
            } else if (heldCall != null) {
                mCallsManager.disconnectCall(heldCall);
                return true;
            }
        } else if (chld == CHLD_TYPE_RELEASEACTIVE_ACCEPTHELD) {
            Log.i(TAG, "CHLD=1: isAnswercallInProgress:" + isAnswercallInProgress);
            if (activeCall == null && ringingCall == null && heldCall == null)
                return false;
            if (activeCall != null) {
                mCallsManager.disconnectCall(activeCall);
                if (ringingCall != null) {
                    if (!isAnswercallInProgress) {
                        mCallsManager.answerCall(ringingCall, VideoProfile.STATE_AUDIO_ONLY);
                        Log.i(TAG, "making isAnswercallInProgress to true");
                        isAnswercallInProgress = true;
                    }
                }
                return true;
            }
            if (ringingCall != null) {
                if (!isAnswercallInProgress) {
                    mCallsManager.answerCall(ringingCall, ringingCall.getVideoState());
                    Log.i(TAG, "CHLD=1: There is ringing call making "
                              + "isAnswercallInProgress to true:");
                    isAnswercallInProgress = true;
                }
            } else if (heldCall != null) {
                mCallsManager.unholdCall(heldCall);
            }
            return true;
        } else if (chld == CHLD_TYPE_HOLDACTIVE_ACCEPTHELD) {
            Log.i(TAG, "CHLD=2: isAnswercallInProgress:" + isAnswercallInProgress);
            if (activeCall != null && activeCall.can(Connection.CAPABILITY_SWAP_CONFERENCE)) {
                activeCall.swapConference();
                Log.i(TAG, "CDMA calls in conference swapped, updating headset");
                updateHeadsetWithCallState(true /* force */);
                return true;
            } else if (ringingCall != null) {
                if (!isAnswercallInProgress) {
                    mCallsManager.answerCall(ringingCall, VideoProfile.STATE_AUDIO_ONLY);
                    Log.i(TAG, "ringing call is present, making isAnswercallInProgress "
                           + "to true");
                    isAnswercallInProgress = true;
                }
                return true;
            } else if (heldCall != null) {
                // CallsManager will hold any active calls when unhold() is called on a
                // currently-held call.
                mCallsManager.unholdCall(heldCall);
                return true;
            } else if (activeCall != null && activeCall.can(Connection.CAPABILITY_HOLD)) {
                mCallsManager.holdCall(activeCall);
                return true;
            }
        } else if (chld == CHLD_TYPE_ADDHELDTOCONF) {
            if (activeCall != null) {
                if (activeCall.can(Connection.CAPABILITY_MERGE_CONFERENCE)) {
                    activeCall.mergeConference();
                    return true;
                } else {
                    List<Call> conferenceable = activeCall.getConferenceableCalls();
                    if (!conferenceable.isEmpty()) {
                        mCallsManager.conference(activeCall, conferenceable.get(0));
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void enforceModifyPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MODIFY_PHONE_STATE, null);
    }

    private void sendListOfCalls(boolean shouldLog) {
        Collection<Call> mCalls = mCallsManager.getCalls();
        for (Call call : mCalls) {
            // We don't send the parent conference call to the bluetooth device.
            // We do, however want to send conferences that have no children to the bluetooth
            // device (e.g. IMS Conference).
            if (!call.isConference() ||
                    (call.isConference() && call
                            .can(Connection.CAPABILITY_CONFERENCE_HAS_NO_CHILDREN))) {
                sendClccForCall(call, shouldLog);
            }
        }
        sendClccEndMarker();
    }

    /**
     * Sends a single clcc (C* List Current Calls) event for the specified call.
     */
    private void sendClccForCall(Call call, boolean shouldLog) {
        boolean isForeground = mCallsManager.getForegroundCall() == call;
        int state = getBtCallState(call, isForeground);
        boolean isPartOfConference = false;
        boolean isConferenceWithNoChildren = call.isConference() && call
                .can(Connection.CAPABILITY_CONFERENCE_HAS_NO_CHILDREN);

        if (state == CALL_STATE_IDLE) {
            return;
        }

        Call conferenceCall = call.getParentCall();
        if (conferenceCall != null) {
            isPartOfConference = true;

            // Run some alternative states for Conference-level merge/swap support.
            // Basically, if call supports swapping or merging at the conference-level, then we need
            // to expose the calls as having distinct states (ACTIVE vs CAPABILITY_HOLD) or the
            // functionality won't show up on the bluetooth device.

            // Before doing any special logic, ensure that we are dealing with an ACTIVE call and
            // that the conference itself has a notion of the current "active" child call.
            Call activeChild = conferenceCall.getConferenceLevelActiveCall();
            if (state == CALL_STATE_ACTIVE && activeChild != null) {
                // Reevaluate state if we can MERGE or if we can SWAP without previously having
                // MERGED.
                boolean shouldReevaluateState =
                        conferenceCall.can(Connection.CAPABILITY_MERGE_CONFERENCE) ||
                        (conferenceCall.can(Connection.CAPABILITY_SWAP_CONFERENCE) &&
                        !conferenceCall.wasConferencePreviouslyMerged());

                if (shouldReevaluateState) {
                    isPartOfConference = false;
                    if (call == activeChild) {
                        state = CALL_STATE_ACTIVE;
                    } else {
                        // At this point we know there is an "active" child and we know that it is
                        // not this call, so set it to HELD instead.
                        state = CALL_STATE_HELD;
                    }
                }
            }
            if (conferenceCall.getState() == CallState.ON_HOLD &&
                    conferenceCall.can(Connection.CAPABILITY_MANAGE_CONFERENCE)) {
                // If the parent IMS CEP conference call is on hold, we should mark this call as
                // being on hold regardless of what the other children are doing.
                state = CALL_STATE_HELD;
            }
        } else if (isConferenceWithNoChildren) {
            // Handle the special case of an IMS conference call without conference event package
            // support.  The call will be marked as a conference, but the conference will not have
            // child calls where conference event packages are not used by the carrier.
            isPartOfConference = true;
        }

        int index = getIndexForCall(call);
        int direction = call.isIncoming() ? 1 : 0;
        final Uri addressUri;
        if (call.getGatewayInfo() != null) {
            addressUri = call.getGatewayInfo().getOriginalAddress();
        } else {
            addressUri = call.getHandle();
        }

        String address = addressUri == null ? null : addressUri.getSchemeSpecificPart();
        if (address != null) {
            address = PhoneNumberUtils.stripSeparators(address);
        }

        int addressType = address == null ? -1 : PhoneNumberUtils.toaFromString(address);

        if (shouldLog) {
            Log.i(this, "sending clcc for call %d, %d, %d, %b, %s, %d",
                    index, direction, state, isPartOfConference, Log.piiHandle(address),
                    addressType);
        }

        if (mBluetoothHeadset != null) {
            mBluetoothHeadset.clccResponse(
                    index, direction, state, 0, isPartOfConference, address, addressType);
        }
    }

    private void sendClccEndMarker() {
        // End marker is recognized with an index value of 0. All other parameters are ignored.
        if (mBluetoothHeadset != null) {
            mBluetoothHeadset.clccResponse(0 /* index */, 0, 0, 0, false, null, 0);
        }
    }

    /**
     * Returns the caches index for the specified call.  If no such index exists, then an index is
     * given (smallest number starting from 1 that isn't already taken).
     */
    private int getIndexForCall(Call call) {
        if (mClccIndexMap.containsKey(call)) {
            return mClccIndexMap.get(call);
        }

        int i = 1;  // Indexes for bluetooth clcc are 1-based.
        while (mClccIndexMap.containsValue(i)) {
            i++;
        }

        // NOTE: Indexes are removed in {@link #onCallRemoved}.
        mClccIndexMap.put(call, i);
        return i;
    }

    /**
     * Sends an update of the current call state to the current Headset.
     *
     * @param force {@code true} if the headset state should be sent regardless if no changes to the
     *      state have occurred, {@code false} if the state should only be sent if the state has
     *      changed.
     */
    private void updateHeadsetWithCallState(boolean force) {
        Call activeCall = mCallsManager.getActiveCall();

        /* Treat ANSWERED state call also as ringing call as BT is not aware of this state */
        Call ringingCall = mCallsManager.getRingingCall();
        Call heldCall = mCallsManager.getHeldCall();

        int bluetoothCallState = getBluetoothCallStateForUpdate();

        String ringingAddress = null;
        int ringingAddressType = 128;
        String ringingName = null;
        if (ringingCall != null && ringingCall.getHandle() != null
            && !ringingCall.isSilentRingingRequested()) {
            ringingAddress = ringingCall.getHandle().getSchemeSpecificPart();
            if (ringingAddress != null) {
                ringingAddressType = PhoneNumberUtils.toaFromString(ringingAddress);
            }
            ringingName = ringingCall.getCallerDisplayName();
            if (TextUtils.isEmpty(ringingName)) {
                ringingName = ringingCall.getName();
            }
        }
        if (ringingAddress == null) {
            ringingAddress = "";
        }

        int numActiveCalls = activeCall == null ? 0 : 1;
        int numHeldCalls = mCallsManager.getNumHeldCalls();
        int numChildrenOfActiveCall = activeCall == null ? 0 : activeCall.getChildCalls().size();

        // Intermediate state for GSM calls which are in the process of being swapped.
        // TODO: Should we be hardcoding this value to 2 or should we check if all top level calls
        //       are held?
        boolean callsPendingSwitch = (numHeldCalls == 2);

        // For conference calls which support swapping the active call within the conference
        // (namely CDMA calls) we need to expose that as a held call in order for the BT device
        // to show "swap" and "merge" functionality.
        boolean ignoreHeldCallChange = false;
        if (activeCall != null && activeCall.isConference() &&
                !activeCall.can(Connection.CAPABILITY_CONFERENCE_HAS_NO_CHILDREN)) {
            if (activeCall.can(Connection.CAPABILITY_SWAP_CONFERENCE)) {
                // Indicate that BT device should show SWAP command by indicating that there is a
                // call on hold, but only if the conference wasn't previously merged.
                numHeldCalls = activeCall.wasConferencePreviouslyMerged() ? 0 : 1;
            } else if (activeCall.can(Connection.CAPABILITY_MERGE_CONFERENCE)) {
                numHeldCalls = 1;  // Merge is available, so expose via numHeldCalls.
            }

            for (Call childCall : activeCall.getChildCalls()) {
                // Held call has changed due to it being combined into a CDMA conference. Keep
                // track of this and ignore any future update since it doesn't really count as
                // a call change.
                if (mOldHeldCall == childCall) {
                    ignoreHeldCallChange = true;
                    break;
                }
            }
        }

        if (mBluetoothHeadset != null &&
                (force ||
                        (!callsPendingSwitch &&
                                (numActiveCalls != mNumActiveCalls ||
                                        numChildrenOfActiveCall != mNumChildrenOfActiveCall ||
                                        numHeldCalls != mNumHeldCalls ||
                                        bluetoothCallState != mBluetoothCallState ||
                                        !TextUtils.equals(ringingAddress, mRingingAddress) ||
                                        ringingAddressType != mRingingAddressType ||
                                (heldCall != mOldHeldCall && !ignoreHeldCallChange))))) {

            // If the call is transitioning into the alerting state, send DIALING first.
            // Some devices expect to see a DIALING state prior to seeing an ALERTING state
            // so we need to send it first.
            boolean sendDialingFirst = mBluetoothCallState != bluetoothCallState &&
                    bluetoothCallState == CALL_STATE_ALERTING;

            if (numActiveCalls > 0) {
                    Log.i(TAG, "updateHeadsetWithCallState: Call active");
                    boolean isCsCall = ((activeCall != null) &&
                            !(activeCall.hasProperty(Connection.PROPERTY_HIGH_DEF_AUDIO) ||
                            activeCall.hasProperty(Connection.PROPERTY_WIFI)));
                    final Intent intent = new Intent(TelecomManager.ACTION_CALL_TYPE);
                    intent.putExtra(TelecomManager.EXTRA_CALL_TYPE_CS, isCsCall);
                    mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            }
            mOldHeldCall = heldCall;
            mNumActiveCalls = numActiveCalls;
            mNumChildrenOfActiveCall = numChildrenOfActiveCall;
            mNumHeldCalls = numHeldCalls;
            mBluetoothCallState = bluetoothCallState;
            mRingingAddress = ringingAddress;
            mRingingAddressType = ringingAddressType;

            if (sendDialingFirst) {
                // Log in full to make logs easier to debug.
                Log.i(TAG, "updateHeadsetWithCallState " +
                        "numActive %s, " +
                        "numHeld %s, " +
                        "callState %s, " +
                        "ringing number %s, " +
                        "ringing type %s, " +
                        "ringing name %s",
                        mNumActiveCalls,
                        mNumHeldCalls,
                        CALL_STATE_DIALING,
                        Log.pii(mRingingAddress),
                        mRingingAddressType,
                        Log.pii(ringingName));
                mBluetoothHeadset.phoneStateChanged(
                        mNumActiveCalls,
                        mNumHeldCalls,
                        CALL_STATE_DIALING,
                        mRingingAddress,
                        mRingingAddressType,
                        ringingName);
            }

            Log.i(TAG, "updateHeadsetWithCallState " +
                    "numActive %s, " +
                    "numHeld %s, " +
                    "callState %s, " +
                    "ringing number %s, " +
                    "ringing type %s, " +
                    "ringing name %s",
                    mNumActiveCalls,
                    mNumHeldCalls,
                    mBluetoothCallState,
                    Log.pii(mRingingAddress),
                    mRingingAddressType,
                    Log.pii(ringingName));

            mBluetoothHeadset.phoneStateChanged(
                    mNumActiveCalls,
                    mNumHeldCalls,
                    mBluetoothCallState,
                    mRingingAddress,
                    mRingingAddressType,
                    ringingName);

            mHeadsetUpdatedRecently = true;
        }
    }

    private int getBluetoothCallStateForUpdate() {
        /* getRingingCall() gets call in RINGING and ANSWERED state. Update BT
         * about ANSWERED call also as RINGING as BT is not aware of this state */
        Call ringingCall = mCallsManager.getRingingCall();
        Call dialingCall = mCallsManager.getOutgoingCall();
        boolean hasOnlyDisconnectedCalls = mCallsManager.hasOnlyDisconnectedCalls();

        //
        // !! WARNING !!
        // You will note that CALL_STATE_WAITING, CALL_STATE_HELD, and CALL_STATE_ACTIVE are not
        // used in this version of the call state mappings.  This is on purpose.
        // phone_state_change() in btif_hf.c is not written to handle these states. Only with the
        // listCalls*() method are WAITING and ACTIVE used.
        // Using the unsupported states here caused problems with inconsistent state in some
        // bluetooth devices (like not getting out of ringing state after answering a call).
        //
        int bluetoothCallState = CALL_STATE_IDLE;
        if (ringingCall != null && !ringingCall.isSilentRingingRequested()) {
            bluetoothCallState = CALL_STATE_INCOMING;
        } else if (dialingCall != null && dialingCall.getState() == CallState.DIALING) {
            bluetoothCallState = CALL_STATE_ALERTING;
        } else if (hasOnlyDisconnectedCalls || mIsDisconnectedTonePlaying) {
            // Keep the DISCONNECTED state until the disconnect tone's playback is done
            bluetoothCallState = CALL_STATE_DISCONNECTED;
        }
        return bluetoothCallState;
    }

    private int getBtCallState(Call call, boolean isForeground) {
        switch (call.getState()) {
            case CallState.NEW:
            case CallState.ABORTED:
            case CallState.DISCONNECTED:
                return CALL_STATE_IDLE;

            case CallState.ACTIVE:
                return CALL_STATE_ACTIVE;

            case CallState.CONNECTING:
            case CallState.SELECT_PHONE_ACCOUNT:
            case CallState.DIALING:
            case CallState.PULLING:
                // Yes, this is correctly returning ALERTING.
                // "Dialing" for BT means that we have sent information to the service provider
                // to place the call but there is no confirmation that the call is going through.
                // When there finally is confirmation, the ringback is played which is referred to
                // as an "alert" tone, thus, ALERTING.
                // TODO: We should consider using the ALERTING terms in Telecom because that
                // seems to be more industry-standard.
                return CALL_STATE_ALERTING;

            case CallState.ON_HOLD:
                return CALL_STATE_HELD;

            case CallState.RINGING:
            case CallState.ANSWERED:
                if (call.isSilentRingingRequested()) {
                    return CALL_STATE_IDLE;
                } else if (isForeground) {
                    return CALL_STATE_INCOMING;
                } else {
                    return CALL_STATE_WAITING;
                }
        }
        return CALL_STATE_IDLE;
    }

    /**
     * Returns the best phone account to use for the given state of all calls.
     * First, tries to return the phone account for the foreground call, second the default
     * phone account for PhoneAccount.SCHEME_TEL.
     */
    private PhoneAccount getBestPhoneAccount() {
        if (mPhoneAccountRegistrar == null) {
            return null;
        }

        Call call = mCallsManager.getForegroundCall();

        PhoneAccount account = null;
        if (call != null) {
            // First try to get the network name of the foreground call.
            account = mPhoneAccountRegistrar.getPhoneAccountOfCurrentUser(
                    call.getTargetPhoneAccount());
        }

        if (account == null) {
            // Second, Try to get the label for the default Phone Account.
            account = mPhoneAccountRegistrar.getPhoneAccountUnchecked(
                    mPhoneAccountRegistrar.getOutgoingPhoneAccountForSchemeOfCurrentUser(
                            PhoneAccount.SCHEME_TEL));
        }
        return account;
    }
}
