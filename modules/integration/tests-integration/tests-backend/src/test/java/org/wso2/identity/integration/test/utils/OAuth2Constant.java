/*
 * Copyright (c) 2019-2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
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

package org.wso2.identity.integration.test.utils;

import org.wso2.identity.integration.common.utils.ISServerConfiguration;

/**
 * OAuth2 constant
 */
public final class OAuth2Constant {

    public static final String OAUTH2_GRANT_TYPE_IMPLICIT = "token";
    public static final String OAUTH2_GRANT_TYPE_CODE = "code";
    public static final String OAUTH2_GRANT_TYPE_CLIENT_CREDENTIALS = "client_credentials";
    public static final String OAUTH2_GRANT_TYPE_RESOURCE_OWNER = "password";
    public static final String OAUTH2_GRANT_TYPE_REFRESH_TOKEN = "refresh_token";
    public static final String OAUTH2_GRANT_TYPE_ORGANIZATION_SWITCH = "organization_switch";
    public static final String OAUTH2_RESPONSE_TYPE_TOKEN = "token";

    public static final String OAUTH2_GRANT_TYPE_AUTHORIZATION_CODE = "authorization_code";
    public static final String AUTHORIZATION_HEADER = "Authorization";

    public final static String ACCESS_TOKEN = "access_token";
    public static final String REFRESH_TOKEN = "refresh_token";
    public static final String ID_TOKEN = "id_token";
    public final static String ACCESS_TOKEN_TYPE = "bearer";
    public final static String OAUTH_VERSION_2 = "OAuth-2.0";
    public static final String OAUTH_2 = "oauth2";
    public final static String REDIRECT_LOCATIONS = "http.protocol.redirect-locations";
    // These constants use the legacy hardcoded base URL to avoid class-load-time calls to
    // ISServerConfiguration.getInstance() which crashes if not yet initialized.
    // In Docker mode, use the dynamic method accessors (getAccessTokenEndpoint(), etc.) instead.
    private static final String LEGACY_BASE_URL = "https://localhost:9853";
    public static final String ACCESS_TOKEN_ENDPOINT = LEGACY_BASE_URL + "/oauth2/token";
    public static final String TOKEN_REVOKE_ENDPOINT = LEGACY_BASE_URL + "/oauth2/revoke";
    public static final String OIDC_LOGOUT_ENDPOINT = LEGACY_BASE_URL + "/oidc/logout";
    public static final String PAR_ENDPOINT = LEGACY_BASE_URL + "/oauth2/par";
    public static final String OAUTH2_DEFAULT_ERROR_URL = LEGACY_BASE_URL + "/authenticationendpoint/oauth2_error.do";
    public static final String USER_INFO_ENDPOINT = LEGACY_BASE_URL + "/oauth2/userinfo?schema=openid";
    public static final String DATA_API_ENDPOINT = LEGACY_BASE_URL + "/api/identity/auth/v1.1/data/OauthConsentKey/";
    public static final String AUTHTOKEN_VALIDATE_SERVICE = "https://localhost:9853/services/" + "OAuth2TokenValidationService";
    public static final String COMMON_AUTH_URL = LEGACY_BASE_URL + "/commonauth";
    public final static String USER_AGENT = "Apache-HttpClient/4.2.5 (java 1.6)";
    public static final String AUTHORIZE_ENDPOINT_URL = LEGACY_BASE_URL + "/oauth2/authorize";
    public static final String APPROVAL_URL = LEGACY_BASE_URL + "/oauth2/authorize";
    public final static String AUTHORIZE_PARAM = "Authorize";
    public static final String TOKEN_VALIDATION_SERVICE_URL = "https://localhost:9853/services/" + "OAuth2TokenValidationService";
    public final static String HTTP_RESPONSE_HEADER_LOCATION = "location";
    public final static String OAUTH2_SCOPE_OPENID = "openid";
    public static final String OAUTH2_SCOPE_OPENID_WITH_INTERNAL_LOGIN = "openid internal_login";
    public final static String OAUTH2_SCOPE_EMAIL = "email";
    public final static String OAUTH2_SCOPE_PHONE = "phone";
    public final static String OAUTH2_SCOPE_ADDRESS = "address";
    public final static String OAUTH2_SCOPE_PROFILE = "profile";
    public final static String OAUTH2_SCOPE_DEFAULT = "";
    public final static String OAUTH_APPLICATION_NAME = "oauthTestApplication";
    public static final String UNSUPPORTED_GRANT_TYPE = "unsupported_grant_type";
    public static final String UNAUTHORIZED_CLIENT = "unauthorized_client";
    public final static String OAUTH_APPLICATION_STATE_REVOKED = "REVOKED";
    public static final String OAUTH_CONSUMER_SECRET = "oauthConsumerSecret";
    public static final String OAUTH_OIDC_REQUEST = "request";

    public final static String CALLBACK_URL = "http://localhost:8490/playground2/oauth2client";
    public final static String CALLBACK_URL_REGEXP = "regexp=http:\\/\\/localhost:8490\\/playground2\\/oauth2client[\\?]?((\\w+)=(\\w+)&?)+";
    public final static String CALLBACK_REQUEST_URL_WITH_PARAMS = "http://localhost:8490/playground2/oauth2client?param=value&param2=value2";
    public final static String AUTHORIZED_USER_URL = "http://localhost:8490/playground2/oauth2-authorize-user.jsp";
    public final static String AUTHORIZED_URL = "http://localhost:8490/playground2/oauth2.jsp";
    public final static String GET_ACCESS_TOKEN_URL = "http://localhost:8490/playground2/oauth2-get-access-token.jsp";
    public final static String ACCESS_RESOURCES_URL = "http://localhost:8490/playground2/oauth2-access-resource.jsp";
    public final static String PLAYGROUND_APP_CONTEXT_ROOT = "/playground2";
    public static final String GRANT_TYPE_PLAYGROUND_NAME = "grantType";
    public static final String CONSUMER_KEY_PLAYGROUND_NAME = "consumerKey";
    public static final String CALLBACKURL_PLAYGROUND_NAME = "callbackurl";
    public static final String AUTHORIZE_ENDPOINT_PLAYGROUND_NAME = "authorizeEndpoint";
    public static final String AUTHORIZE_PLAYGROUND_NAME = "authorize";
    public static final String SCOPE_PLAYGROUND_NAME = "scope";
    public static final String AUTH_CODE_BODY_ELEMENT = "Authorization Code";

    public final static String WSO2_CLAIM_DIALECT_ROLE="http://wso2.org/claims/role";
    public static final String GRANT_TYPE_NAME = "grant_type";
    public static final String AUTHORIZATION_CODE_NAME = "code";
    public static final String REDIRECT_URI_NAME = "redirect_uri";
    public static final String BASIC_HEADER = "Basic";
    public static final String INVALID_GRANT_ERROR = "invalid_grant";
    public static final String SESSION_DATA_KEY_CONSENT = "sessionDataKeyConsent";
    public static final String SESSION_DATA_KEY = "sessionDataKey";
    public static final String INVALID_CLIENT = "invalid_client";
    public static final String TRAVELOCITY_APP_CONTEXT_ROOT = "/travelocity.com";
    public static final String OAUTH2_GRANT_TYPE_SAML2_BEARER = "urn:ietf:params:oauth:grant-type:saml2-bearer";

    // OAuth 2 request parameters.
    public static final String OAUTH2_RESPONSE_TYPE = "response_type";
    public static final String OAUTH2_CLIENT_ID = "client_id";
    public static final String OAUTH2_CLIENT_SECRET = "client_secret";
    public static final String OAUTH2_REDIRECT_URI = "redirect_uri";
    public static final String OAUTH2_SCOPE = "scope";
    public static final String OAUTH2_NONCE = "nonce";
    public static final String INTRO_SPEC_ENDPOINT = LEGACY_BASE_URL + "/oauth2/introspect";
    public static final String TENANT_INTRO_SPEC_ENDPOINT = LEGACY_BASE_URL + "/t/wso2.com/oauth2/introspect";
    public static final String TENANT_USER_INFO_ENDPOINT = LEGACY_BASE_URL + "/t/wso2.com/oauth2/userinfo?schema=openid";
    public static final String TENANT_DATA_API_ENDPOINT = LEGACY_BASE_URL + "/t/wso2.com/api/identity/auth/v1.1/data/OauthConsentKey/";
    public static final String TENANT_TOKEN_REVOKE_ENDPOINT = LEGACY_BASE_URL + "/t/wso2.com/oauth2/revoke";

    public static final String SCOPE_ENDPOINT = LEGACY_BASE_URL + "/api/server/v1/oidc/scopes";
    public static final String TENANT_SCOPE_ENDPOINT = LEGACY_BASE_URL + "/t/wso2.com/api/server/v1/oidc/scopes";
    public static final String OAUTH2_RESPONSE_MODE = "response_mode";
    public static final String RESPONSE_MODE_QUERY = "query";
    public static final String RESPONSE_MODE_FRAGMENT = "fragment";
    public static final String RESPONSE_MODE_JWT = "jwt";
    public static final String RESPONSE_MODE_QUERY_JWT = "query.jwt";
    public static final String RESPONSE_MODE_FRAGMENT_JWT = "fragment.jwt";

    public static final String RESPONSE_TYPE_CODE_ID_TOKEN = "code id_token";
    public static final String OAUTH2_AUTHORIZATION_DETAILS = "authorization_details";

    // Tenanted urls.
    public final static String TENANT_PLACEHOLDER = "<TENANT_PLACEHOLDER>";
    public static final String TENANT_COMMON_AUTH_URL = LEGACY_BASE_URL + "/t/<TENANT_PLACEHOLDER>/commonauth";
    public static final String TENANT_APPROVAL_URL = LEGACY_BASE_URL + "/t/<TENANT_PLACEHOLDER>/oauth2/authorize";
    public static final String TENANT_TOKEN_ENDPOINT = LEGACY_BASE_URL + "/t/<TENANT_PLACEHOLDER>/oauth2/token";
    public static final String TENANT_INTROSPECT_ENDPOINT = LEGACY_BASE_URL + "/t/<TENANT_PLACEHOLDER>/oauth2/introspect";

    public static final String FIDP_PARAM = "fidp";

    public static final String SUBJECT_TOKEN = "subject_token";
    public static final class PlaygroundAppPaths {

        public static final String callBackPath = "/oauth2client";
        public static final String homePagePath = "/index.jsp";
        public static final String appResetPath = "/oauth2.jsp?reset=true";
        public static final String appAuthorizePath = "/oauth2.jsp";
        public static final String appUserAuthorizePath = "/oauth2-authorize-user.jsp";
        public static final String accessTokenRequestPath = "/oauth2-get-access-token.jsp";
        public static final String resourceAccessPath = "/oauth2-access-resource.jsp";
    }

    private OAuth2Constant() {
	}

    // --- Dynamic URL methods for Docker mode ---

    private static String getBaseUrl() {
        if (ISServerConfiguration.isDockerMode()) {
            return ISServerConfiguration.getInstance().getBaseUrl();
        }
        return "https://localhost:9853";
    }

    public static String getOidcLogoutEndpoint() { return getBaseUrl() + "/oidc/logout"; }
    public static String getAccessTokenEndpoint() { return getBaseUrl() + "/oauth2/token"; }
    public static String getTokenRevokeEndpoint() { return getBaseUrl() + "/oauth2/revoke"; }
    public static String getParEndpoint() { return getBaseUrl() + "/oauth2/par"; }
    public static String getOAuth2DefaultErrorUrl() { return getBaseUrl() + "/authenticationendpoint/oauth2_error.do"; }
    public static String getUserInfoEndpoint() { return getBaseUrl() + "/oauth2/userinfo?schema=openid"; }
    public static String getDataApiEndpoint() { return getBaseUrl() + "/api/identity/auth/v1.1/data/OauthConsentKey/"; }
    public static String getCommonAuthUrl() { return getBaseUrl() + "/commonauth"; }
    public static String getAuthorizeEndpointUrl() { return getBaseUrl() + "/oauth2/authorize"; }
    public static String getIntrospectEndpoint() { return getBaseUrl() + "/oauth2/introspect"; }
    public static String getTenantIntrospectEndpoint() { return getBaseUrl() + "/t/wso2.com/oauth2/introspect"; }
    public static String getTenantUserInfoEndpoint() { return getBaseUrl() + "/t/wso2.com/oauth2/userinfo?schema=openid"; }
    public static String getTenantDataApiEndpoint() { return getBaseUrl() + "/t/wso2.com/api/identity/auth/v1.1/data/OauthConsentKey/"; }
    public static String getTenantTokenRevokeEndpoint() { return getBaseUrl() + "/t/wso2.com/oauth2/revoke"; }
    public static String getScopeEndpoint() { return getBaseUrl() + "/api/server/v1/oidc/scopes"; }
    public static String getTenantScopeEndpoint() { return getBaseUrl() + "/t/wso2.com/api/server/v1/oidc/scopes"; }
    public static String getTenantCommonAuthUrl(String tenant) { return getBaseUrl() + "/t/" + tenant + "/commonauth"; }
    public static String getTenantApprovalUrl(String tenant) { return getBaseUrl() + "/t/" + tenant + "/oauth2/authorize"; }
    public static String getTenantTokenEndpoint(String tenant) { return getBaseUrl() + "/t/" + tenant + "/oauth2/token"; }
    public static String getTenantIntrospectEndpoint(String tenant) { return getBaseUrl() + "/t/" + tenant + "/oauth2/introspect"; }
    public static String getTokenValidationServiceUrl() { return CommonConstants.getServiceUrl() + "OAuth2TokenValidationService"; }
    public static String getTenantCommonAuthUrlForPlaceholder() { return getBaseUrl() + "/t/<TENANT_PLACEHOLDER>/commonauth"; }
    public static String getTenantApprovalUrlForPlaceholder() { return getBaseUrl() + "/t/<TENANT_PLACEHOLDER>/oauth2/authorize"; }
    public static String getTenantTokenEndpointForPlaceholder() { return getBaseUrl() + "/t/<TENANT_PLACEHOLDER>/oauth2/token"; }
    public static String getTenantIntrospectEndpointForPlaceholder() { return getBaseUrl() + "/t/<TENANT_PLACEHOLDER>/oauth2/introspect"; }
}
