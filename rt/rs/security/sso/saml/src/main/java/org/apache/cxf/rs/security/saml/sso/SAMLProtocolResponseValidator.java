/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cxf.rs.security.saml.sso;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.security.auth.callback.CallbackHandler;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.common.util.Base64Exception;
import org.apache.cxf.common.util.Base64Utility;
import org.apache.cxf.rs.security.common.SecurityUtils;
import org.apache.cxf.rs.security.xml.EncryptionUtils;
import org.apache.cxf.staxutils.StaxUtils;
import org.apache.wss4j.common.crypto.Crypto;
import org.apache.wss4j.common.ext.WSSecurityException;
import org.apache.wss4j.common.saml.SAMLKeyInfo;
import org.apache.wss4j.common.saml.SAMLUtil;
import org.apache.wss4j.common.saml.SamlAssertionWrapper;
import org.apache.wss4j.common.util.KeyUtils;
import org.apache.wss4j.dom.WSConstants;
import org.apache.wss4j.dom.WSDocInfo;
import org.apache.wss4j.dom.WSSConfig;
import org.apache.wss4j.dom.handler.RequestData;
import org.apache.wss4j.dom.saml.WSSSAMLKeyInfoProcessor;
import org.apache.wss4j.dom.validate.Credential;
import org.apache.wss4j.dom.validate.SamlAssertionValidator;
import org.apache.wss4j.dom.validate.SignatureTrustValidator;
import org.apache.wss4j.dom.validate.Validator;
import org.apache.xml.security.encryption.XMLCipher;
import org.apache.xml.security.encryption.XMLEncryptionException;
import org.apache.xml.security.utils.Constants;
import org.joda.time.DateTime;
import org.opensaml.security.SAMLSignatureProfileValidator;
import org.opensaml.xml.encryption.EncryptedData;
import org.opensaml.xml.security.x509.BasicX509Credential;
import org.opensaml.xml.signature.KeyInfo;
import org.opensaml.xml.signature.Signature;
import org.opensaml.xml.signature.SignatureValidator;
import org.opensaml.xml.validation.ValidationException;
import org.opensaml.xml.validation.ValidatorSuite;

/**
 * Validate a SAML (1.1 or 2.0) Protocol Response. It validates the Response against the specs,
 * the signature of the Response (if it exists), and any internal Assertion stored in the Response 
 * - including any signature. It validates the status code of the Response as well.
 */
public class SAMLProtocolResponseValidator {
    
    public static final String SAML2_STATUSCODE_SUCCESS = 
        "urn:oasis:names:tc:SAML:2.0:status:Success";
    public static final String SAML1_STATUSCODE_SUCCESS = "Success";
    
    private static final Logger LOG = LogUtils.getL7dLogger(SAMLProtocolResponseValidator.class);
    
    private Validator assertionValidator = new SamlAssertionValidator();
    private Validator signatureValidator = new SignatureTrustValidator();
    private boolean keyInfoMustBeAvailable = true;
    
    /**
     * The time in seconds in the future within which the NotBefore time of an incoming 
     * Assertion is valid. The default is 60 seconds.
     */
    private int futureTTL = 60;
    
    /**
     * Validate a SAML 2 Protocol Response
     * @param samlResponse
     * @param sigCrypto
     * @param callbackHandler
     * @throws WSSecurityException
     */
    public void validateSamlResponse(
        org.opensaml.saml2.core.Response samlResponse,
        Crypto sigCrypto,
        CallbackHandler callbackHandler
    ) throws WSSecurityException {
        // Check the Status Code
        if (samlResponse.getStatus() == null
            || samlResponse.getStatus().getStatusCode() == null) {
            LOG.fine("Either the SAML Response Status or StatusCode is null");
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }
        if (!SAML2_STATUSCODE_SUCCESS.equals(samlResponse.getStatus().getStatusCode().getValue())) {
            LOG.fine(
                "SAML Status code of " + samlResponse.getStatus().getStatusCode().getValue()
                + "does not equal " + SAML2_STATUSCODE_SUCCESS
            );
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }
        
        if (samlResponse.getIssueInstant() != null) {
            DateTime currentTime = new DateTime();
            currentTime = currentTime.plusSeconds(futureTTL);
            if (samlResponse.getIssueInstant().isAfter(currentTime)) {
                LOG.fine("SAML Response IssueInstant not met");
                throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
            }
        }
        
        validateResponseAgainstSchemas(samlResponse);
        validateResponseSignature(samlResponse, sigCrypto, callbackHandler);

        Document doc = samlResponse.getDOM().getOwnerDocument();
        // Decrypt any encrypted Assertions and add them to the Response (note that this will break any
        // signature on the Response)
        for (org.opensaml.saml2.core.EncryptedAssertion assertion : samlResponse.getEncryptedAssertions()) {
            
            Element decAssertion = decryptAssertion(assertion, sigCrypto, callbackHandler);
            
            SamlAssertionWrapper wrapper = new SamlAssertionWrapper(decAssertion);
            samlResponse.getAssertions().add(wrapper.getSaml2());
        }

        // Validate Assertions
        for (org.opensaml.saml2.core.Assertion assertion : samlResponse.getAssertions()) {
            SamlAssertionWrapper wrapper = new SamlAssertionWrapper(assertion);
            validateAssertion(wrapper, sigCrypto, callbackHandler, doc);
        }
    }
    
    /**
     * Validate a SAML 1.1 Protocol Response
     * @param samlResponse
     * @param sigCrypto
     * @param callbackHandler
     * @throws WSSecurityException
     */
    public void validateSamlResponse(
        org.opensaml.saml1.core.Response samlResponse,
        Crypto sigCrypto,
        CallbackHandler callbackHandler
    ) throws WSSecurityException {
        // Check the Status Code
        if (samlResponse.getStatus() == null
            || samlResponse.getStatus().getStatusCode() == null
            || samlResponse.getStatus().getStatusCode().getValue() == null) {
            LOG.fine("Either the SAML Response Status or StatusCode is null");
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }
        String statusValue = samlResponse.getStatus().getStatusCode().getValue().getLocalPart();
        if (!SAML1_STATUSCODE_SUCCESS.equals(statusValue)) {
            LOG.fine(
                "SAML Status code of " + samlResponse.getStatus().getStatusCode().getValue()
                + "does not equal " + SAML1_STATUSCODE_SUCCESS
            );
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }

        if (samlResponse.getIssueInstant() != null) {
            DateTime currentTime = new DateTime();
            currentTime = currentTime.plusSeconds(futureTTL);
            if (samlResponse.getIssueInstant().isAfter(currentTime)) {
                LOG.fine("SAML Response IssueInstant not met");
                throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
            }
        }
        
        validateResponseAgainstSchemas(samlResponse);
        validateResponseSignature(samlResponse, sigCrypto, callbackHandler);

        // Validate Assertions
        for (org.opensaml.saml1.core.Assertion assertion : samlResponse.getAssertions()) {
            SamlAssertionWrapper wrapper = new SamlAssertionWrapper(assertion);
            validateAssertion(
                wrapper, sigCrypto, callbackHandler, samlResponse.getDOM().getOwnerDocument()
            );
        }
    }
    
    /**
     * Validate the Response against the schemas
     */
    private void validateResponseAgainstSchemas(
        org.opensaml.saml2.core.Response samlResponse
    ) throws WSSecurityException {
        // Validate SAML Response against schemas
        ValidatorSuite schemaValidators = 
            org.opensaml.Configuration.getValidatorSuite("saml2-core-schema-validator");
        try {
            schemaValidators.validate(samlResponse);
        } catch (ValidationException e) {
            LOG.log(Level.FINE, "Saml Validation error: " + e.getMessage(), e);
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }
    }
    
    /**
     * Validate the Response against the schemas
     */
    private void validateResponseAgainstSchemas(
        org.opensaml.saml1.core.Response samlResponse
    ) throws WSSecurityException {
        // Validate SAML Response against schemas
        ValidatorSuite schemaValidators = 
            org.opensaml.Configuration.getValidatorSuite("saml1-core-schema-validator");
        try {
            schemaValidators.validate(samlResponse);
        } catch (ValidationException e) {
            LOG.log(Level.FINE, "Saml Validation error: " + e.getMessage(), e);
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }
    }
    
    /**
     * Validate the Response signature (if it exists)
     */
    private void validateResponseSignature(
        org.opensaml.saml2.core.Response samlResponse,
        Crypto sigCrypto,
        CallbackHandler callbackHandler
    ) throws WSSecurityException {
        if (!samlResponse.isSigned()) {
            return;
        }
        
        validateResponseSignature(
            samlResponse.getSignature(), samlResponse.getDOM().getOwnerDocument(),
            sigCrypto, callbackHandler
        );
    }
    
    /**
     * Validate the Response signature (if it exists)
     */
    private void validateResponseSignature(
        org.opensaml.saml1.core.Response samlResponse,
        Crypto sigCrypto,
        CallbackHandler callbackHandler
    ) throws WSSecurityException {
        if (!samlResponse.isSigned()) {
            return;
        }
        
        validateResponseSignature(
            samlResponse.getSignature(), samlResponse.getDOM().getOwnerDocument(),
            sigCrypto, callbackHandler
        );
    }
    
    /**
     * Validate the response signature
     */
    private void validateResponseSignature(
        Signature signature, 
        Document doc,
        Crypto sigCrypto,
        CallbackHandler callbackHandler
    ) throws WSSecurityException {
        RequestData requestData = new RequestData();
        requestData.setSigVerCrypto(sigCrypto);
        WSSConfig wssConfig = WSSConfig.getNewInstance();
        requestData.setWssConfig(wssConfig);
        requestData.setCallbackHandler(callbackHandler);
        
        SAMLKeyInfo samlKeyInfo = null;
        
        KeyInfo keyInfo = signature.getKeyInfo();
        if (keyInfo != null) {
            try {
                samlKeyInfo = 
                    SAMLUtil.getCredentialFromKeyInfo(
                        keyInfo.getDOM(), new WSSSAMLKeyInfoProcessor(requestData, new WSDocInfo(doc)), sigCrypto
                    );
            } catch (WSSecurityException ex) {
                LOG.log(Level.FINE, "Error in getting KeyInfo from SAML Response: " + ex.getMessage(), ex);
                throw ex;
            }
        } else if (!keyInfoMustBeAvailable) {
            samlKeyInfo = createKeyInfoFromDefaultAlias(sigCrypto);
        }
        if (samlKeyInfo == null) {
            LOG.fine("No KeyInfo supplied in the SAMLResponse signature");
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }
        
        // Validate Signature against profiles
        validateSignatureAgainstProfiles(signature, samlKeyInfo);

        // Now verify trust on the signature
        Credential trustCredential = new Credential();
        trustCredential.setPublicKey(samlKeyInfo.getPublicKey());
        trustCredential.setCertificates(samlKeyInfo.getCerts());

        try {
            signatureValidator.validate(trustCredential, requestData);
        } catch (WSSecurityException e) {
            LOG.log(Level.FINE, "Error in validating signature on SAML Response: " + e.getMessage(), e);
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }
    }
    
    protected SAMLKeyInfo createKeyInfoFromDefaultAlias(Crypto sigCrypto) throws WSSecurityException {
        try {
            X509Certificate[] certs = SecurityUtils.getCertificates(sigCrypto, 
                                                                    sigCrypto.getDefaultX509Identifier());
            SAMLKeyInfo samlKeyInfo = new SAMLKeyInfo(new X509Certificate[]{certs[0]});
            samlKeyInfo.setPublicKey(certs[0].getPublicKey());
            return samlKeyInfo;
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Error in loading the certificates: " + ex.getMessage(), ex);
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILED_SIGNATURE, ex);
        }
    }
    
    /**
     * Validate a signature against the profiles
     */
    private void validateSignatureAgainstProfiles(
        Signature signature, 
        SAMLKeyInfo samlKeyInfo
    ) throws WSSecurityException {
        // Validate Signature against profiles
        SAMLSignatureProfileValidator validator = new SAMLSignatureProfileValidator();
        try {
            validator.validate(signature);
        } catch (ValidationException ex) {
            LOG.log(Level.FINE, "Error in validating the SAML Signature: " + ex.getMessage(), ex);
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }

        BasicX509Credential credential = new BasicX509Credential();
        if (samlKeyInfo.getCerts() != null) {
            credential.setEntityCertificate(samlKeyInfo.getCerts()[0]);
        } else if (samlKeyInfo.getPublicKey() != null) {
            credential.setPublicKey(samlKeyInfo.getPublicKey());
        } else {
            LOG.fine("Can't get X509Certificate or PublicKey to verify signature");
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }
        SignatureValidator sigValidator = new SignatureValidator(credential);
        try {
            sigValidator.validate(signature);
        } catch (ValidationException ex) {
            LOG.log(Level.FINE, "Error in validating the SAML Signature: " + ex.getMessage(), ex);
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }
    }
    
    /**
     * Validate an internal Assertion
     */
    private void validateAssertion(
        SamlAssertionWrapper assertion,
        Crypto sigCrypto,
        CallbackHandler callbackHandler,
        Document doc
    ) throws WSSecurityException {
        Credential credential = new Credential();
        credential.setSamlAssertion(assertion);
        
        RequestData requestData = new RequestData();
        requestData.setSigVerCrypto(sigCrypto);
        WSSConfig wssConfig = WSSConfig.getNewInstance();
        requestData.setWssConfig(wssConfig);
        requestData.setCallbackHandler(callbackHandler);
        
        if (assertion.isSigned()) {
            if (assertion.getSaml1() != null) {
                assertion.getSaml1().getDOM().setIdAttributeNS(null, "AssertionID", true);
            } else {
                assertion.getSaml2().getDOM().setIdAttributeNS(null, "ID", true);
            }
            
            // Verify the signature
            try {
                Signature sig = assertion.getSignature();
                WSDocInfo docInfo = new WSDocInfo(sig.getDOM().getOwnerDocument());
                
                SAMLKeyInfo samlKeyInfo = null;
                
                KeyInfo keyInfo = sig.getKeyInfo();
                if (keyInfo != null) {
                    samlKeyInfo = SAMLUtil.getCredentialFromKeyInfo(
                        keyInfo.getDOM(), new WSSSAMLKeyInfoProcessor(requestData, docInfo), sigCrypto
                    );
                } else if (!keyInfoMustBeAvailable) {
                    samlKeyInfo = createKeyInfoFromDefaultAlias(sigCrypto);
                }

                if (samlKeyInfo == null) {
                    LOG.fine("No KeyInfo supplied in the SAMLResponse assertion signature");
                    throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
                }
                
                assertion.verifySignature(samlKeyInfo);
                
                assertion.parseSubject(
                    new WSSSAMLKeyInfoProcessor(requestData, new WSDocInfo(doc)),
                    requestData.getSigVerCrypto(), 
                    requestData.getCallbackHandler()
                );
            } catch (WSSecurityException e) {
                LOG.log(Level.FINE, "Assertion failed signature validation", e);
                throw e;
            }
        }
        
        // Validate the Assertion & verify trust in the signature
        try {
            assertionValidator.validate(credential, requestData);
        } catch (WSSecurityException ex) {
            LOG.log(Level.FINE, "Assertion validation failed: " + ex.getMessage(), ex);
            throw ex;
        }
    }
    
    private Element decryptAssertion(
        org.opensaml.saml2.core.EncryptedAssertion assertion, Crypto sigCrypto, CallbackHandler callbackHandler
    ) throws WSSecurityException {
        EncryptedData encryptedData = assertion.getEncryptedData();
        Element encryptedDataDOM = encryptedData.getDOM();
                
        Element encKeyElement = getNode(assertion.getDOM(), WSConstants.ENC_NS, "EncryptedKey", 0);
        if (encKeyElement == null) {
            encKeyElement = getNode(encryptedDataDOM, WSConstants.ENC_NS, "EncryptedKey", 0);
        }
        if (encKeyElement == null) {
            LOG.log(Level.FINE, "EncryptedKey element is not available");
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }
        
        X509Certificate cert = loadCertificate(sigCrypto, encKeyElement);
        if (cert == null) {
            LOG.fine("X509Certificate cannot be retrieved from EncryptedKey element");
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }
        
        // now start decrypting
        String keyEncAlgo = getEncodingMethodAlgorithm(encKeyElement);
        String digestAlgo = getDigestMethodAlgorithm(encKeyElement);
        
        Element cipherValue = getNode(encKeyElement, WSConstants.ENC_NS, "CipherValue", 0);
        if (cipherValue == null) {
            LOG.fine("CipherValue element is not available");
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }

        if (callbackHandler == null) {
            LOG.fine("A CallbackHandler must be configured to decrypt encrypted Assertions");
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }
        
        PrivateKey key = null;
        try {
            key = sigCrypto.getPrivateKey(cert, callbackHandler);
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Encrypted key can not be decrypted", ex);
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }
        Cipher cipher = 
                EncryptionUtils.initCipherWithKey(keyEncAlgo, digestAlgo, Cipher.DECRYPT_MODE, key);
        byte[] decryptedBytes = null;
        try {
            byte[] encryptedBytes = Base64Utility.decode(cipherValue.getTextContent().trim());
            decryptedBytes = cipher.doFinal(encryptedBytes);
        } catch (Base64Exception ex) {
            LOG.log(Level.FINE, "Base64 decoding has failed", ex);
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Encrypted key can not be decrypted", ex);
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }
        
        String symKeyAlgo = getEncodingMethodAlgorithm(encryptedDataDOM);
        
        byte[] decryptedPayload = null;
        try {
            decryptedPayload = decryptPayload(encryptedDataDOM, decryptedBytes, symKeyAlgo);
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Payload can not be decrypted", ex);
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }
        
        Document payloadDoc = null;
        try {
            payloadDoc = StaxUtils.read(new InputStreamReader(new ByteArrayInputStream(decryptedPayload),
                                               "UTF-8"));
            return payloadDoc.getDocumentElement();
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Payload document can not be created", ex);
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }
    }
        
    private Element getNode(Element parent, String ns, String name, int index) {
        NodeList list = parent.getElementsByTagNameNS(ns, name);
        if (list != null && list.getLength() >= index + 1) {
            return (Element)list.item(index);
        } 
        return null;
    }


    private X509Certificate loadCertificate(Crypto crypto, Element encKeyElement) throws WSSecurityException {
        Element certNode = 
            getNode(encKeyElement, Constants.SignatureSpecNS, "X509Certificate", 0);
        if (certNode != null) {
            try {
                return SecurityUtils.loadX509Certificate(crypto, certNode);
            } catch (Exception ex) {
                LOG.log(Level.FINE, "X509Certificate can not be created", ex);
                throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
            }
        }
    
        certNode = getNode(encKeyElement, Constants.SignatureSpecNS, "X509IssuerSerial", 0);
        if (certNode != null) {
            try {
                return SecurityUtils.loadX509IssuerSerial(crypto, certNode);
            } catch (Exception ex) {
                LOG.log(Level.FINE, "X509Certificate can not be created", ex);
                throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
            }
        }

        return null;
    }
    
    private String getEncodingMethodAlgorithm(Element parent) throws WSSecurityException {
        Element encMethod = getNode(parent, WSConstants.ENC_NS, "EncryptionMethod", 0);
        if (encMethod == null) {
            LOG.fine("EncryptionMethod element is not available");
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }
        return encMethod.getAttribute("Algorithm");
    }
    
    private String getDigestMethodAlgorithm(Element parent) {
        Element encMethod = getNode(parent, WSConstants.ENC_NS, "EncryptionMethod", 0);
        if (encMethod != null) {
            Element digestMethod = getNode(encMethod, WSConstants.SIG_NS, "DigestMethod", 0);
            if (digestMethod != null) {
                return digestMethod.getAttributeNS(null, "Algorithm");
            }
        }
        return null;
    }
        
    
    private byte[] decryptPayload(
        Element root, byte[] secretKeyBytes, String symEncAlgo
    ) throws WSSecurityException {
        SecretKey key = KeyUtils.prepareSecretKey(symEncAlgo, secretKeyBytes);
        try {
            XMLCipher xmlCipher = 
                EncryptionUtils.initXMLCipher(symEncAlgo, XMLCipher.DECRYPT_MODE, key);
            return xmlCipher.decryptToByteArray(root);
        } catch (XMLEncryptionException ex) {
            throw new WSSecurityException(WSSecurityException.ErrorCode.UNSUPPORTED_ALGORITHM, ex);
        }
    }

    public void setKeyInfoMustBeAvailable(boolean keyInfoMustBeAvailable) {
        this.keyInfoMustBeAvailable = keyInfoMustBeAvailable;
    }

    public int getFutureTTL() {
        return futureTTL;
    }

    public void setFutureTTL(int futureTTL) {
        this.futureTTL = futureTTL;
    }
    
}
