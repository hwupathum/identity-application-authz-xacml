/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.application.authz.xacml.handler.impl;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.w3c.dom.Document;
import org.wso2.balana.utils.Constants.PolicyConstants;
import org.wso2.balana.utils.exception.PolicyBuilderException;
import org.wso2.balana.utils.policy.PolicyBuilder;
import org.wso2.balana.utils.policy.dto.RequestElementDTO;
import org.wso2.carbon.identity.application.authentication.framework.context.AuthenticationContext;
import org.wso2.carbon.identity.application.authentication.framework.exception.FrameworkException;
import org.wso2.carbon.identity.application.authentication.framework.handler.authz.AuthorizationHandler;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkUtils;
import org.wso2.carbon.identity.application.authz.xacml.constants.XACMLAppAuthzConstants;
import org.wso2.carbon.identity.application.authz.xacml.internal.AppAuthzDataholder;
import org.wso2.carbon.identity.base.IdentityConstants;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.entitlement.EntitlementException;
import org.wso2.carbon.identity.entitlement.ui.EntitlementPolicyConstants;
import org.wso2.carbon.identity.entitlement.ui.dto.RequestDTO;
import org.wso2.carbon.identity.entitlement.ui.dto.RowDTO;
import org.wso2.carbon.identity.entitlement.ui.util.PolicyCreatorUtil;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

public class XACMLBasedAuthorizationHandler implements AuthorizationHandler {

    private static final Log log = LogFactory.getLog(XACMLBasedAuthorizationHandler.class);
    public static final String DECISION_XPATH = "/Response/Result/Decision/text()";
    private static volatile XACMLBasedAuthorizationHandler instance;

    public static XACMLBasedAuthorizationHandler getInstance() {

        if (instance == null) {
            synchronized (XACMLBasedAuthorizationHandler.class) {
                if (instance == null) {
                    instance = new XACMLBasedAuthorizationHandler();
                }
            }
        }
        return instance;
    }

    /**
     * Executes the authorization flow
     *
     * @param request  request
     * @param response response
     * @param context  context
     */
    @Override
    public boolean isAuthorized(HttpServletRequest request, HttpServletResponse response,
                                AuthenticationContext context) {

        if (log.isDebugEnabled()) {
            log.debug("In policy authorization flow...");
        }

        if (context != null) {
            try {
//                get the ip from request and add as a authctx property because the request won't available at PIP
                context.addParameter(IdentityConstants.USER_IP, IdentityUtil.getClientIpAddress(request));
                FrameworkUtils.addAuthenticationContextToCache(context.getContextIdentifier(), context);

                //TODO: "RequestDTO" and "PolicyCreatorUtil" is taken from entitlement.ui. Need to reconsider of
                // using the ui bundle
                RequestDTO requestDTO = createRequestDTO(context);
                RequestElementDTO requestElementDTO = PolicyCreatorUtil.createRequestElementDTO(requestDTO);

                String requestString = PolicyBuilder.getInstance().buildRequest(requestElementDTO);
                if (log.isDebugEnabled()) {
                    log.debug("XACML Authorization request :\n" + requestString);
                }
                String responseString =
                        AppAuthzDataholder.getInstance().getEntitlementService().getDecision(requestString);
                if (log.isDebugEnabled()) {
                    log.debug("XACML Authorization response :\n" + responseString);
                }
                Boolean isAuthorized = evaluateXACMLResponse(responseString);
                FrameworkUtils.removeAuthenticationContextFromCache(context.getContextIdentifier());
                if (isAuthorized) {
                    return true;
                }
                //todo: audit log if not authorized
            } catch (PolicyBuilderException e) {
                log.error("Policy Builder Exception occurred", e);
            } catch (EntitlementException e) {
                log.error("Entitlement Exception occurred", e);
            } catch (FrameworkException e) {
                log.error("Error when evaluating the XACML response", e);
            }
        }
        return false;
    }

    private RequestDTO createRequestDTO(AuthenticationContext context) {

        List<RowDTO> rowDTOs = new ArrayList<>();
        RowDTO contextIdentifierDTO =
                createRowDTO(context.getContextIdentifier(),
                        XACMLAppAuthzConstants.AUTH_CTX_ID, XACMLAppAuthzConstants.AUTH_CATEGORY);
        RowDTO spDTO =
                createRowDTO(context.getServiceProviderName(),
                        XACMLAppAuthzConstants.SP_NAME_ID, XACMLAppAuthzConstants.AUTH_CATEGORY);
        RowDTO spDomainDTO =
                createRowDTO(context.getTenantDomain(),
                        XACMLAppAuthzConstants.SP_DOMAIN_ID, XACMLAppAuthzConstants.AUTH_CATEGORY);
        RowDTO usernameDTO =
                createRowDTO(context.getTenantDomain(),
                        XACMLAppAuthzConstants.USERNAME_ID, XACMLAppAuthzConstants.AUTH_CATEGORY);
        RowDTO userStoreDomainDTO =
                createRowDTO(context.getSequenceConfig().getAuthenticatedUser().getUserStoreDomain(),
                        XACMLAppAuthzConstants.USER_STORE_ID, XACMLAppAuthzConstants.AUTH_CATEGORY);
        RowDTO userTenantDomainDTO =
                createRowDTO(context.getSequenceConfig().getAuthenticatedUser().getTenantDomain(),
                        XACMLAppAuthzConstants.USER_TENANT_DOMAIN_ID, XACMLAppAuthzConstants.AUTH_CATEGORY);
        String subject = null;
        if (context.getSequenceConfig() != null && context.getSequenceConfig().getAuthenticatedUser() != null) {
            subject = context.getSequenceConfig().getAuthenticatedUser().toString();
        }
        if (subject != null) {
            RowDTO subjectDTO =
                    createRowDTO(subject, PolicyConstants.SUBJECT_ID_DEFAULT, PolicyConstants.SUBJECT_CATEGORY_URI);
            rowDTOs.add(subjectDTO);
        }
        rowDTOs.add(contextIdentifierDTO);
        rowDTOs.add(spDTO);
        rowDTOs.add(spDomainDTO);
        rowDTOs.add(usernameDTO);
        rowDTOs.add(userStoreDomainDTO);
        rowDTOs.add(userTenantDomainDTO);
        RequestDTO requestDTO = new RequestDTO();
        requestDTO.setRowDTOs(rowDTOs);
        return requestDTO;
    }

    private RowDTO createRowDTO(String resourceName, String attributeId, String categoryValue) {

        RowDTO rowDTOTenant = new RowDTO();
        rowDTOTenant.setAttributeValue(resourceName);
        rowDTOTenant.setAttributeDataType(EntitlementPolicyConstants.STRING_DATA_TYPE);
        rowDTOTenant.setAttributeId(attributeId);
        rowDTOTenant.setCategory(categoryValue);
        return rowDTOTenant;

    }

    private boolean evaluateXACMLResponse(String xacmlResponse) throws FrameworkException {

        try {
            DocumentBuilderFactory dbf = IdentityUtil.getSecuredDocumentBuilderFactory();
            DocumentBuilder db = dbf.newDocumentBuilder();
            InputSource is = new InputSource();
            is.setCharacterStream(new StringReader(xacmlResponse));
            Document doc = db.parse(is);

            XPath xpath = XPathFactory.newInstance().newXPath();
            XPathExpression expr = xpath.compile(DECISION_XPATH);
            String decision = (String) expr.evaluate(doc, XPathConstants.STRING);
            if (decision.equalsIgnoreCase(EntitlementPolicyConstants.RULE_EFFECT_PERMIT)
                    || decision.equalsIgnoreCase(EntitlementPolicyConstants.RULE_EFFECT_NOT_APPLICABLE)) {
                return true;
            }
        } catch (ParserConfigurationException | SAXException | XPathExpressionException | IOException e) {
            throw new FrameworkException("Exception occurred while xacmlResponse processing", e);
        }
        return false;
    }
}