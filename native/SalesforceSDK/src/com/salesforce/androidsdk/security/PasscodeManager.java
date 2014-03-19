/*
 * Copyright (c) 2014, salesforce.com, inc.
 * All rights reserved.
 * Redistribution and use of this software in source and binary forms, with or
 * without modification, are permitted provided that the following conditions
 * are met:
 * - Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * - Neither the name of salesforce.com, inc. nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission of salesforce.com, inc.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.salesforce.androidsdk.security;

import java.util.List;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.Handler;
import android.util.Log;

import com.salesforce.androidsdk.accounts.UserAccount;
import com.salesforce.androidsdk.app.SalesforceSDKManager;
import com.salesforce.androidsdk.app.UUIDManager;
import com.salesforce.androidsdk.util.EventsObservable;
import com.salesforce.androidsdk.util.EventsObservable.EventType;

/**
 * This class manages the inactivity timeout, and keeps track of if the UI should locked etc.
 *
 * @author wmathurin
 * @author bhariharan
 */
public class PasscodeManager  {

	// UUID keys
	private static final String VKEY = "vkey";
	private static final String VSUFFIX = "vsuffix";
	private static final String VPREFIX = "vprefix";
	private static final String EKEY = "ekey";
	private static final String ESUFFIX = "esuffix";
	private static final String EPREFIX = "eprefix";
	
    // Default min passcode length
    public static final int MIN_PASSCODE_LENGTH = 4;

    // Key in preference for the passcode
    private static final String KEY_PASSCODE ="passcode";

    // Private preference where we stored the passcode (hashed)
    private static final String PASSCODE_PREF_NAME = "user";

    // Private preference where we stored the org settings.
    private static final String MOBILE_POLICY_PREF = "mobile_policy";

    // Key in preference for the access timeout.
    private static final String KEY_TIMEOUT = "access_timeout";

    // Key in preference for the passcode length.
    private static final String KEY_PASSCODE_LENGTH = "passcode_length";

    // Request code used to start passcode activity
    public static final int PASSCODE_REQUEST_CODE = 777;

    // this is a hash of the passcode to be used as part of the key to encrypt/decrypt oauth tokens
    // It's using a different salt/key than the one used to verify the entry
    private String passcodeHash;

    // Misc
    private HashConfig verificationHashConfig;
    private HashConfig encryptionHashConfig;
    private int failedPasscodeAttempts;
    private Activity frontActivity;
    private Handler handler;
    private long lastActivity;
    private boolean locked;
    private int timeoutMs;
    private int minPasscodeLength;
    private LockChecker lockChecker;

    /**
     * Parameterized constructor.
     *
     * @param ctx Context.
     * @param verificationHashConfig Verification HashConfig.
     * @param encryptionHashConfig Encryption HashConfig.
     */
   public PasscodeManager(Context ctx) {
	   this(ctx,
		   new HashConfig(UUIDManager.getUuId(VPREFIX),
				   UUIDManager.getUuId(VSUFFIX), UUIDManager.getUuId(VKEY)),
		   new HashConfig(UUIDManager.getUuId(EPREFIX),
				   UUIDManager.getUuId(ESUFFIX), UUIDManager.getUuId(EKEY)));
   }

   public PasscodeManager(Context ctx, HashConfig verificationHashConfig,
		   HashConfig encryptionHashConfig) {
       this.minPasscodeLength = MIN_PASSCODE_LENGTH;
       this.lastActivity = now();
       this.verificationHashConfig = verificationHashConfig;
       this.encryptionHashConfig = encryptionHashConfig;
       readMobilePolicy(ctx);

       // Locked at app startup if you're authenticated.
       this.locked = true;
       lockChecker = new LockChecker(); 
   }

   /**
    * Returns the timeout value for the specified account.
    *
    * @param account UserAccount instance.
    * @return Timeout value.
    */
   	public int getTimeoutMsForOrg(UserAccount account) {
   		if (account == null) {
   			return 0;
   		}
   		final Context context = SalesforceSDKManager.getInstance().getAppContext();
        final SharedPreferences sp = context.getSharedPreferences(MOBILE_POLICY_PREF
        		+ account.getOrgLevelSharedPrefSuffix(), Context.MODE_PRIVATE);
        return sp.getInt(KEY_TIMEOUT, 0);
   	}

    /**
     * Returns the minimum passcode length for the specified account.
     *
     * @param account UserAccount instance.
     * @return Minimum passcode length.
     */
    public int getPasscodeLengthForOrg(UserAccount account) {
    	if (account == null) {
    		return MIN_PASSCODE_LENGTH;
    	}
    	final Context context = SalesforceSDKManager.getInstance().getAppContext();
        final SharedPreferences sp = context.getSharedPreferences(MOBILE_POLICY_PREF
        		+ account.getOrgLevelSharedPrefSuffix(), Context.MODE_PRIVATE);
        return sp.getInt(KEY_PASSCODE_LENGTH, MIN_PASSCODE_LENGTH);
    }

    /**
     * Stores the mobile policy for the specified account.
     *
     * @param account UserAccount instance.
     * @param timeout Timeout value, in ms.
     * @param passLen Minimum passcode length.
     */
    public void storeMobilePolicyForOrg(UserAccount account, int timeout, int passLen) {
    	if (account == null) {
    		return;
    	}
    	final Context context = SalesforceSDKManager.getInstance().getAppContext();
        final SharedPreferences sp = context.getSharedPreferences(MOBILE_POLICY_PREF
        		+ account.getOrgLevelSharedPrefSuffix(), Context.MODE_PRIVATE);
        final Editor e = sp.edit();
        e.putInt(KEY_TIMEOUT, timeoutMs);
        e.putInt(KEY_PASSCODE_LENGTH, minPasscodeLength);
        e.commit();
    }

    /**
     * Stores the mobile policy in a private file.
     *
     * @param context Context.
     */
    private void storeMobilePolicy(Context context) {

        // Context will be null only in test runs.
        if (context != null) {
            final SharedPreferences sp = context.getSharedPreferences(MOBILE_POLICY_PREF, Context.MODE_PRIVATE);
            Editor e = sp.edit();
            e.putInt(KEY_TIMEOUT, timeoutMs);
            e.putInt(KEY_PASSCODE_LENGTH, minPasscodeLength);
            e.commit();
        }
    }

    /**
     * Reads the mobile policy from a private file.
     *
     * @param context Context.
     */
    private void readMobilePolicy(Context context) {

        // Context will be null only in test runs.
        if (context != null) {
            final SharedPreferences sp = context.getSharedPreferences(MOBILE_POLICY_PREF, Context.MODE_PRIVATE);
            if (!sp.contains(KEY_TIMEOUT) || !sp.contains(KEY_PASSCODE_LENGTH)) {
                timeoutMs = 0;
                minPasscodeLength = MIN_PASSCODE_LENGTH;
                storeMobilePolicy(context);
                return;
            }
            timeoutMs = sp.getInt(KEY_TIMEOUT, 0);
            minPasscodeLength = sp.getInt(KEY_PASSCODE_LENGTH, MIN_PASSCODE_LENGTH);
        }
    }

    /**
     * Reset this passcode manager: delete stored passcode and reset fields to their starting value
     */
    public void reset(Context ctx) {
    	final List<UserAccount> users = SalesforceSDKManager.getInstance().getUserAccountManager().getAuthenticatedUsers();
    	if (users != null) {
    		for (final UserAccount account : users) {
    			reset(ctx, account);
    		}
    	}
    	lastActivity = now();
        locked = true;
        failedPasscodeAttempts = 0;
        passcodeHash = null;
        SharedPreferences sp = ctx.getSharedPreferences(PASSCODE_PREF_NAME,
        		Context.MODE_PRIVATE);
        Editor e = sp.edit();
        e.remove(KEY_PASSCODE);
        e.commit();
        timeoutMs = 0;
        minPasscodeLength = MIN_PASSCODE_LENGTH;
        storeMobilePolicy(ctx);
        handler = null;
    }

    /**
     * Resets the passcode policies for a particular org upon logout.
     *
     * @param context Context.
     * @param account User account.
     */
    public void reset(Context context, UserAccount account) {
    	if (account == null) {
    		return;
    	}
        final SharedPreferences sp = context.getSharedPreferences(MOBILE_POLICY_PREF
        		+ account.getOrgLevelSharedPrefSuffix(), Context.MODE_PRIVATE);
        final Editor e = sp.edit();
        e.clear();
        e.commit();
    }

    /**
     * Enable/disable passcode screen.
     */
    public void setEnabled(boolean enabled) {
        if (enabled) {
            handler = new Handler();
            handler.postDelayed(lockChecker, 20 * 1000);
        } else {
            if (handler != null) {
                handler.removeCallbacks(lockChecker);
            }
            handler = null;
        }
    }

    /**
     * @return true if passcode manager is enabled.
     */
    public boolean isEnabled() {
        return (handler != null);
    }

    /**
     * @return the new failure count
     */
    public int addFailedPasscodeAttempt() {
        return ++failedPasscodeAttempts;
    }

    /**
     * @param ctx
     * @param passcode
     * @return true if passcode matches the one stored (hashed) in private preference
     */
    public boolean check(Context ctx, String passcode) {
        SharedPreferences sp = ctx.getSharedPreferences(PASSCODE_PREF_NAME, Context.MODE_PRIVATE);
        String hashedPasscode = sp.getString(KEY_PASSCODE, null);
        hashedPasscode = Encryptor.removeNewLine(hashedPasscode);
        if (hashedPasscode != null) {
            return hashedPasscode.equals(hashForVerification(passcode));
        }

        /*
         * If the stored passcode hash is null, there is no passcode.
         */
        return true;
    }

    /**
     * Store the given passcode (hashed) in private preference
     * @param ctx
     * @param passcode
     */
    public void store(Context ctx, String passcode) {
        SharedPreferences sp = ctx.getSharedPreferences(PASSCODE_PREF_NAME, Context.MODE_PRIVATE);
        Editor e = sp.edit();
        e.putString(KEY_PASSCODE, hashForVerification(passcode));
        e.commit();
    }

    /**
     * @param ctx
     * @return true if passcode was already created
     */
    public boolean hasStoredPasscode(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PASSCODE_PREF_NAME, Context.MODE_PRIVATE);
        return sp.contains(KEY_PASSCODE);
    }

    /**
     * @return number of failed passcode attempts
     */
    public int getFailedPasscodeAttempts() {
        return failedPasscodeAttempts;
    }

    /**
     * @return a hash of the passcode that can be used for encrypting oauth tokens
     */
    public String getPasscodeHash() {
        return passcodeHash;
    }

    /**
     * Sets the passcode hash, used ONLY in tests.
     *
     * @param passcodeHash Passcode hash.
     */
    public void setPasscodeHash(String passcodeHash) {
    	if (SalesforceSDKManager.getInstance().getIsTestRun()) {
        	this.passcodeHash = passcodeHash;
    	}
    }

    /**
     * @return true if locked
     */
    public boolean isLocked() {
        return timeoutMs > 0 && locked;
    }

    /**
     * @param ctx
     */
    public void lock(Context ctx) {
        locked = true;
        showLockActivity(ctx);
        EventsObservable.get().notifyEvent(EventType.AppLocked);
    }

    /**
     * @param newFrontActivity
     * @param registerActivity
     * @return
     */
    public boolean lockIfNeeded(Activity newFrontActivity, boolean registerActivity) {
        if (newFrontActivity != null)
            frontActivity = newFrontActivity;
        if (isEnabled() && (isLocked() || shouldLock())) {
            lock(frontActivity);
            return true;
        } else {
            if (registerActivity) updateLast();
            return false;
        }
    }

    /**
     * @param a
     */
    public void nolongerFrontActivity(Activity a) {
        if (frontActivity == a)
            frontActivity = null;
    }


    /**
     * To be called by passcode protected activity when being paused
     */
    public void onPause(Activity ctx) {

        // Disables passcode manager.
        setEnabled(false);
    }

    /**
     * To be called by passcode protected activity when being resumed
     * When passcode screen is about to be shown, false is returned, the activity will be resumed once
     * the user has successfully enter her passcode
     *
     * @return true if the resume should be allowed to continue and false otherwise
     */
    public boolean onResume(Activity ctx) {

        // Enables passcode manager.
        setEnabled(true);

        // Brings up passcode screen if needed.
        lockIfNeeded(ctx, true);

        // If locked, do nothing - when the app gets unlocked we will be back here.
        return !isLocked();
    }

    /**
     * To be called by passcode protected activity whenever there is a user interaction
     */
    public void recordUserInteraction() {
        updateLast();
    }

    /**
     * Called when the access timeout for the org changes.
     *
     * @param newTimeout New access timeout value.
     */
    public void setTimeoutMs(int newTimeout) {

        // Access timeout hasn't changed.
        if (timeoutMs == newTimeout) {
            return;
        }

        /*
         * Either access timeout has changed from one non-zero value to another,
         * which doesn't alter the passcode situation, or the app goes from
         * no passcode to passcode, which will trigger the passcode creation flow.
         */
        if (timeoutMs == 0 || (timeoutMs > 0 && newTimeout > 0)) {
            timeoutMs = newTimeout;
            storeMobilePolicy(SalesforceSDKManager.getInstance().getAppContext());
            return;
        }

        // Passcode to no passcode.
        timeoutMs = newTimeout;
        SalesforceSDKManager.getInstance().changePasscode(passcodeHash, null);
        reset(SalesforceSDKManager.getInstance().getAppContext());
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public int getMinPasscodeLength() {
        return minPasscodeLength;
    }

    public void setMinPasscodeLength(int minPasscodeLength) {
    	/*
    	 * TODO: Need to trigger the change passcode flow here - through the
    	 * UI, informing the user that the policy has changed and a new
    	 * longer PIN needs to be used.
    	 */
    	if (minPasscodeLength <= this.minPasscodeLength) {
    		// TODO: Do nothing here - no need for new flow in this case.
    	}
        this.minPasscodeLength = minPasscodeLength;
        storeMobilePolicy(SalesforceSDKManager.getInstance().getAppContext());
    }

    public boolean shouldLock() {
        return timeoutMs > 0 && now() >= (lastActivity + timeoutMs);
    }

    public void showLockActivity(Context ctx) {
        if (ctx == null) {
        	return;
        }
        Intent i = new Intent(ctx, SalesforceSDKManager.getInstance().getPasscodeActivity());
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        if (ctx == SalesforceSDKManager.getInstance().getAppContext()) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        if (ctx instanceof Activity) {
            ((Activity) ctx).startActivityForResult(i, PASSCODE_REQUEST_CODE);
        } else {
            ctx.startActivity(i);
        }
    }

    public void unlock(String passcode) {
        locked = false;
        failedPasscodeAttempts = 0;
        passcodeHash = hashForEncryption(passcode);
        updateLast();
        EventsObservable.get().notifyEvent(EventType.AppUnlocked);
    }

    protected long now() {
        return System.currentTimeMillis();
    }

    private void updateLast() {
        lastActivity = now();
    }

    public String hashForVerification(String passcode) {
    	return hash(passcode, verificationHashConfig);
    }
    
    public String hashForEncryption(String passcode) {
    	return hash(passcode, encryptionHashConfig);
    }
    
    private String hash(String passcode, HashConfig hashConfig) {
        return Encryptor.hash(hashConfig.prefix + passcode + hashConfig.suffix, hashConfig.key);
    }

    /**
     * Thread checking periodically to see how much has elapsed since the last recorded activity
      * When that elapsed time exceed timeoutMs, it locks the app
      */
    private class LockChecker implements Runnable {
        public void run() {
            try {
            	if (isEnabled()) {
            		Log.d("LockChecker:run",  "isLocked:" + locked + " elapsedSinceLastActivity:" + ((now() - lastActivity)/1000) + " timeout:" + (timeoutMs / 1000));
            	}
                if (!locked)
                    lockIfNeeded(null, false);
            } finally {
                if (handler != null) {
                    handler.postDelayed(this, 20 * 1000);
                }
            }
        }
    }

    /**
     * Key for hashing and salts to be preprended and appended to data to increase entropy.
     */
    public static class HashConfig {
        public final String prefix;
        public final String suffix;
        public final String key;
        public HashConfig(String prefix, String suffix, String key) {
            this.prefix = prefix;
            this.suffix = suffix;
            this.key = key;
        }
    }
}
