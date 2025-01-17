/*
 * Copyright 2012 Giesecke & Devrient GmbH.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.simalliance.openmobileapi.service.security;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.MissingResourceException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import org.simalliance.openmobileapi.service.Channel;
import org.simalliance.openmobileapi.service.ISmartcardServiceCallback;
import org.simalliance.openmobileapi.service.SmartcardService;
import org.simalliance.openmobileapi.service.Terminal;
import org.simalliance.openmobileapi.service.security.ChannelAccess.ACCESS;
import org.simalliance.openmobileapi.service.security.ara.AraController;

import org.simalliance.openmobileapi.service.security.arf.ArfController;


public class AccessControlEnforcer {

    private PackageManager mPackageManager = null;

    private AraController mAraController = null;
    private boolean mUseAra = true;

    private ArfController mArfController = null;
    private boolean mUseArf = false;

    private AccessRuleCache mAccessRuleCache = null;
    private boolean mRulesRead = false;

    private Terminal mTerminal = null;

    private ChannelAccess mInitialChannelAccess = new ChannelAccess();
    private boolean mFullAccess = false;

    private boolean initialized = false;

    protected boolean[] mNfcEventFlags = null;

    private final String ACCESS_CONTROL_ENFORCER = "Access Control Enforcer: ";

    public AccessControlEnforcer( Terminal terminal ) {

        mTerminal = terminal;
        mAccessRuleCache = new AccessRuleCache();
    }

    public PackageManager getPackageManager() {
        return mPackageManager;
    }

    public void setPackageManager(PackageManager packageManager) {
        this.mPackageManager = packageManager;
    }

    public Terminal getTerminal(){
        return mTerminal;
    }

    public AccessRuleCache getAccessRuleCache(){
        return mAccessRuleCache;
    }

    public static byte[] getDefaultAccessControlAid(){
        return AraController.getAraMAid();
    }

    public synchronized void reset() {
        // Destroy any previous Controler
        // in order to reset the ACE
        Log.i(SmartcardService.LOG_TAG, "Reset the ACE for terminal:" + mTerminal.getName());
        mAraController = null;
        mArfController = null;
    }

    public synchronized boolean initialize(boolean loadAtStartup, ISmartcardServiceCallback callback) {
        boolean status = true;
        String denyMsg = "";
        // allow access to set up access control for a channel
        mInitialChannelAccess.setApduAccess(ChannelAccess.ACCESS.ALLOWED);
        mInitialChannelAccess.setNFCEventAccess(ChannelAccess.ACCESS.ALLOWED);
        mInitialChannelAccess.setAccess(ChannelAccess.ACCESS.ALLOWED, "");

        readSecurityProfile();

        if(!mTerminal.getName().startsWith("SIM")) {
            // When SE is not the UICC then it's allowed to grant full access if no
            // rules can be retreived.
            mFullAccess = true;
        }

        // 1 - Let's try to use ARA
        if( mUseAra && mAraController == null)
            mAraController = new AraController(this);

        if( mUseAra ){
            try {
                mAraController.initialize(loadAtStartup, callback);
                // disable other access methods

                Log.i(SmartcardService.LOG_TAG, "ARA applet is used for:" + mTerminal.getName());
                mUseArf = false;
                mFullAccess = false;

            } catch( Exception e ) {

             // ARA cannot be used since we got an exception during initialization
                mUseAra = false;
                denyMsg = e.getLocalizedMessage();

                 if( e instanceof MissingResourceException ) {
                     if(mTerminal.getName().startsWith("SIM")) {
                         // If the SE is a UICC then a possible explanation could simply
                         // be due to the fact that the UICC is old and doesn't
                         // support logical channel (and is not compliant with GP spec).
                         // in this case we should simply act as if no ARA was available
                         Log.w(SmartcardService.LOG_TAG, "Got MissingResourceException: Does the UICC support logical channel?");
                         Log.w(SmartcardService.LOG_TAG, "Full message: " +  e.getMessage());
                     } else {
                         // If the SE is not a UICC then this exception means that something
                         // wrong has occured!
                         throw new MissingResourceException( e.getMessage(), "", "");
                     }
                } else if( mAraController.isNoSuchElement() ) {
                     Log.i(SmartcardService.LOG_TAG, "No ARA applet found in: " + mTerminal.getName());
                } else {
                    // ARA is available but doesn't work properly.
                    // We are going to disable everything per security req.
                    Log.i(SmartcardService.LOG_TAG, "AccessControlEnforcer - Problem accessing ARA, Access DENIED. " + e.getLocalizedMessage());

                    // access is denied for any terminal if exception during accessing ARA has any other reason.
                    mUseArf = false;
                    mFullAccess = false;
                    status = false;
                }
            }
        }

        // 2 - Let's try to use ARF since ARA cannot be used
        if(mUseArf && !mTerminal.getName().startsWith("SIM")) {
            Log.i(SmartcardService.LOG_TAG, "Disable ARF for terminal: " + mTerminal.getName() + " (ARF is only available for UICC)");
            mUseArf = false; // Arf is only supproted on UICC
        }

        if( mUseArf && mArfController == null)
            mArfController = new ArfController(this);

        if( mUseArf && mArfController != null) {
            try {
                mArfController.initialize(callback);
                // disable other access methods
                Log.i(SmartcardService.LOG_TAG, "ARF rules are used for:" + mTerminal.getName());
                mFullAccess = false;
            } catch( Exception e ) {
                // ARF cannot be used since we got an exception
                mUseArf = false;
                status = false;
                denyMsg = e.getLocalizedMessage();
                Log.e(SmartcardService.LOG_TAG, e.getMessage() );
            }
        }

        /* 3 - Let's grant full access since neither ARA nor ARF can be used */
        if(mFullAccess) {
            mInitialChannelAccess.setApduAccess(ChannelAccess.ACCESS.ALLOWED);
            mInitialChannelAccess.setNFCEventAccess(ChannelAccess.ACCESS.ALLOWED);
            mInitialChannelAccess.setAccess(ChannelAccess.ACCESS.ALLOWED, "");

            Log.i(SmartcardService.LOG_TAG, "Full access granted for:" + mTerminal.getName());
        }

        /* 4 - Let's block everything since neither ARA, ARF or fullaccess can be used */
        if(!mUseArf && !mUseAra && !mFullAccess) {
            mInitialChannelAccess.setApduAccess(ChannelAccess.ACCESS.DENIED);
            mInitialChannelAccess.setNFCEventAccess(ChannelAccess.ACCESS.DENIED);
            mInitialChannelAccess.setAccess(ChannelAccess.ACCESS.DENIED, denyMsg);

            Log.i(SmartcardService.LOG_TAG, "Deny any access to:" + mTerminal.getName());
        }
        mRulesRead = status;
        initialized = true;
        return status;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public static Certificate decodeCertificate(byte[] certData) throws CertificateException {
        return CertificateFactory.getInstance("X.509").generateCertificate(
                                                            new ByteArrayInputStream(certData));
    }

    public synchronized void checkCommand(Channel channel, byte[] command) {

        ChannelAccess ca = channel.getChannelAccess();
        if (ca == null) {
            throw new SecurityException(ACCESS_CONTROL_ENFORCER + "Channel access not set");
        }

        String reason = ca.getReason();
        if (reason.length() == 0) {
            reason = "Command not allowed!";
        }

        if (ca.getAccess() != ACCESS.ALLOWED ) {
            throw new SecurityException(ACCESS_CONTROL_ENFORCER + reason);
        }
        if (ca.isUseApduFilter()) {
            ApduFilter[] accessConditions = ca.getApduFilter();
            if (accessConditions == null || accessConditions.length == 0) {
                throw new SecurityException(ACCESS_CONTROL_ENFORCER + "Access Rule not available: " + reason);
            }
            for (ApduFilter ac : accessConditions) {
                if (CommandApdu.compareHeaders(command, ac.getMask(), ac.getApdu())) {

                    return;
                }
            }

            throw new SecurityException(ACCESS_CONTROL_ENFORCER + "Access Rule does not match: " + reason);
        }
        if (ca.getApduAccess() != ChannelAccess.ACCESS.ALLOWED) {
            throw new SecurityException(ACCESS_CONTROL_ENFORCER + "APDU access NOT allowed" );
        }
    }

    public ChannelAccess setUpChannelAccess(
            byte[] aid,
            String packageName) {
        ChannelAccess channelAccess = null;

        // check result of channel access during initialization procedure
        if( mInitialChannelAccess.getAccess() == ChannelAccess.ACCESS.DENIED ){
            throw new SecurityException( ACCESS_CONTROL_ENFORCER + "access denied: " + mInitialChannelAccess.getReason() );
        }
        // this is the new GP Access Control Enforcer implementation
        if( mUseAra || mUseArf ){

            try {
                channelAccess = internal_setUpChannelAccess(aid, packageName);
            } catch( Exception e ) {
                if( e instanceof MissingResourceException ) {
                    throw new MissingResourceException( ACCESS_CONTROL_ENFORCER + e.getMessage(), "", "");
                } else {
                    // access is denied for any terminal if exception during accessing ARA has any other reason.
                    throw new SecurityException( ACCESS_CONTROL_ENFORCER + "access denied: " + e.getMessage() );
                }
            }
        }

        if( channelAccess == null // precautionary check
                || (channelAccess.getApduAccess() != ChannelAccess.ACCESS.ALLOWED
                    && !channelAccess.isUseApduFilter())) {

            if( mFullAccess ){
                // if full access is set then we reuse the initial channel access,
                // since we got so far it allows everything with a descriptive reason.
                channelAccess = mInitialChannelAccess;
            } else {
                throw new SecurityException( ACCESS_CONTROL_ENFORCER + "no APDU access allowed!" );
            }
        }

        channelAccess.setPackageName(packageName);

        return channelAccess.clone();
    }

    private synchronized ChannelAccess internal_setUpChannelAccess(byte[] aid, String packageName) {

        ChannelAccess channelAccess;
        if (packageName == null || packageName.isEmpty()) {
            throw new SecurityException("package names must be specified");
        }

        try {
            // estimate SHA-1 hash value of the device application's certificate.
            Certificate[] appCerts = getAPPCerts(packageName);

            // APP certificates must be available => otherwise Exception
            if (appCerts == null || appCerts.length == 0) {
                throw new SecurityException("Application certificates are invalid or do not exist.");
            }


            channelAccess = getAccessRule(aid, appCerts );

        } catch (Exception exp) {
            throw new SecurityException(exp.getMessage());
        }

        return channelAccess;
    }

    public ChannelAccess getAccessRule( byte[] aid, Certificate[] appCerts ) throws SecurityException, CertificateEncodingException {

        ChannelAccess channelAccess = null;

        // if read all is true get rule from cache.
        if( mRulesRead ){
            // get rules from internal storage
            channelAccess = mAccessRuleCache.findAccessRule( aid, appCerts );
        }

        // if no rule was found return an empty access rule
        // with all access denied.
        if( channelAccess == null ){
            channelAccess = new ChannelAccess();
            channelAccess.setAccess(ChannelAccess.ACCESS.DENIED, "no access rule found!" );
            channelAccess.setApduAccess(ChannelAccess.ACCESS.DENIED);
            channelAccess.setNFCEventAccess(ChannelAccess.ACCESS.DENIED);
        }
        return channelAccess;
    }


    /**
     * Returns Certificate chain for one package.
     *
     * @param packageName
     * @return
     * @throws CertificateException
     * @throws NoSuchAlgorithmException
     * @throws SecurityException
     */
    public Certificate[] getAPPCerts(String packageName)
             throws CertificateException, NoSuchAlgorithmException, SecurityException {

        if(packageName == null || packageName.length() == 0) {
            throw new SecurityException("Package Name not defined");
        }

        PackageInfo foundPkgInfo;

        try {
            foundPkgInfo = mPackageManager.getPackageInfo(packageName,
                                                    PackageManager.GET_SIGNATURES);
        } catch (NameNotFoundException ne) {
            throw new SecurityException("Package does not exist");
        }

         if (foundPkgInfo == null) {
             throw new SecurityException("Package does not exist");
         }

         ArrayList<Certificate> appCerts = new ArrayList<>();

         for (Signature signature : foundPkgInfo.signatures) {
            appCerts.add(decodeCertificate(signature.toByteArray()));
         }
         return appCerts.toArray(new Certificate[appCerts.size()]);
    }

    public static byte[] getAppCertHash(Certificate appCert) throws CertificateEncodingException
    {
        /**
         * Note: This loop is needed as workaround for a bug in Android 2.3.
         * After a failed certificate verification in a previous step the
         * MessageDigest.getInstance("SHA") call will fail with the
         * AlgorithmNotSupported exception. But a second try will normally
         * succeed.
         */
        MessageDigest md = null;
        for (int i = 0; i < 10; i++) {
            try {
                md = MessageDigest.getInstance("SHA");
                break;
            } catch (Exception ignore) {
            }
        }
        if (md == null) {
            throw new SecurityException("Hash can not be computed");
        }
        return md.digest(appCert.getEncoded());
    }

    public synchronized boolean[] isNFCEventAllowed(
            byte[] aid,
            String[] packageNames) {
        if( mUseAra || mUseArf ){
            return internal_isNFCEventAllowed(aid, packageNames);
        } else {
            // 2012-09-27
            // if ARA and ARF is not available and terminal DOES NOT belong to a UICC -> mFullAccess is true
            // if ARA and ARF is not available and terminal belongs to a UICC -> mFullAccess is false
            boolean[] ret = new boolean[packageNames.length];
            for( int i = 0; i < ret.length; i++ ){
                ret[i] = this.mFullAccess;
            }
            return ret;
        }
    }

    private synchronized boolean[] internal_isNFCEventAllowed(byte[] aid, String[] packageNames) {
        // the NFC Event Flags boolean array is created and filled in internal_enableAccessConditions.
        mNfcEventFlags = new boolean[packageNames.length];
        int i=0;
        ChannelAccess channelAccess;
        for( String packageName : packageNames ) {
            // estimate SHA-1 hash value of the device application's certificate.
                Certificate[] appCerts;
                try {
                    appCerts = getAPPCerts(packageName);

                    // APP certificates must be available => otherwise Exception
                    if (appCerts == null || appCerts.length == 0) {
                        throw new SecurityException("Application certificates are invalid or do not exist.");
                    }

                    channelAccess = getAccessRule(aid, appCerts);
                    mNfcEventFlags[i] = (channelAccess.getNFCEventAccess() == ChannelAccess.ACCESS.ALLOWED);

                } catch (Exception e) {
                    Log.w(SmartcardService.LOG_TAG, " Access Rules for NFC: " + e.getLocalizedMessage());
                    mNfcEventFlags[i] = false;
                }
                i++;
        }
        return mNfcEventFlags;
    }


    public void dump(PrintWriter writer, String prefix) {
       writer.println(prefix + SmartcardService.LOG_TAG + ":");
       prefix += "  ";

       writer.println(prefix + "mUseArf: " + mUseArf);
       writer.println(prefix + "mUseAra: " + mUseAra);
       writer.println(prefix + "mInitialChannelAccess:");
       writer.println(prefix + "  " + mInitialChannelAccess.toString());
       writer.println();

       /* Dump the access rule cache */
       if(mAccessRuleCache != null) mAccessRuleCache.dump(writer, prefix);
    }

    private void readSecurityProfile() {
            mUseArf = false;
            mUseAra = false;
            mFullAccess = true; // Per default we don't grant full access.
        Log.i(SmartcardService.LOG_TAG, "Allowed ACE mode: ara=" + mUseAra + " arf=" + mUseArf + " fullaccess=" + mFullAccess );
    }

}
