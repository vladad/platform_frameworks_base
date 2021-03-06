/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.security.net.config;

import com.android.org.conscrypt.TrustManagerImpl;

import android.util.ArrayMap;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.X509TrustManager;

/**
 * {@link X509TrustManager} that implements the trust anchor and pinning for a
 * given {@link NetworkSecurityConfig}.
 * @hide
 */
public class NetworkSecurityTrustManager implements X509TrustManager {
    // TODO: Replace this with a general X509TrustManager and use duck-typing.
    private final TrustManagerImpl mDelegate;
    private final NetworkSecurityConfig mNetworkSecurityConfig;

    public NetworkSecurityTrustManager(NetworkSecurityConfig config) {
        if (config == null) {
            throw new NullPointerException("config must not be null");
        }
        mNetworkSecurityConfig = config;
        // TODO: Create our own better KeyStoreImpl
        try {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null);
            int certNum = 0;
            for (TrustAnchor anchor : mNetworkSecurityConfig.getTrustAnchors()) {
                store.setEntry(String.valueOf(certNum++),
                        new KeyStore.TrustedCertificateEntry(anchor.certificate),
                        null);
            }
            mDelegate = new TrustManagerImpl(store);
        } catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        throw new CertificateException("Client authentication not supported");
    }

    @Override
    public void checkServerTrusted(X509Certificate[] certs, String authType)
            throws CertificateException {
        List<X509Certificate> trustedChain =
                mDelegate.checkServerTrusted(certs, authType, (String) null);
        checkPins(trustedChain);
    }

    private void checkPins(List<X509Certificate> chain) throws CertificateException {
        PinSet pinSet = mNetworkSecurityConfig.getPins();
        if (pinSet.pins.isEmpty()
                || System.currentTimeMillis() > pinSet.expirationTime
                || !isPinningEnforced(chain)) {
            return;
        }
        Set<String> pinAlgorithms = pinSet.getPinAlgorithms();
        Map<String, MessageDigest> digestMap = new ArrayMap<String, MessageDigest>(
                pinAlgorithms.size());
        for (int i = chain.size() - 1; i >= 0 ; i--) {
            X509Certificate cert = chain.get(i);
            byte[] encodedSPKI = cert.getPublicKey().getEncoded();
            for (String algorithm : pinAlgorithms) {
                MessageDigest md = digestMap.get(algorithm);
                if (md == null) {
                    try {
                        md = MessageDigest.getInstance(algorithm);
                    } catch (GeneralSecurityException e) {
                        throw new RuntimeException(e);
                    }
                    digestMap.put(algorithm, md);
                }
                if (pinSet.pins.contains(new Pin(algorithm, md.digest(encodedSPKI)))) {
                    return;
                }
            }
        }

        // TODO: Throw a subclass of CertificateException which indicates a pinning failure.
        throw new CertificateException("Pin verification failed");
    }

    private boolean isPinningEnforced(List<X509Certificate> chain) throws CertificateException {
        if (chain.isEmpty()) {
            return false;
        }
        X509Certificate anchorCert = chain.get(chain.size() - 1);
        TrustAnchor chainAnchor = null;
        // TODO: faster lookup
        for (TrustAnchor anchor : mNetworkSecurityConfig.getTrustAnchors()) {
            if (anchor.certificate.equals(anchorCert)) {
                chainAnchor = anchor;
                break;
            }
        }
        if (chainAnchor == null) {
            throw new CertificateException("Trusted chain does not end in a TrustAnchor");
        }
        return !chainAnchor.overridesPins;
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }
}
