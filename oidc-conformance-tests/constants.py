# ----------------------------------------------------------------------------
#  Copyright 2021 WSO2, Inc. http://www.wso2.org
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

ALIAS = "test"

BASE_URL = "https://localhost:9443"

DCR_ENDPOINT = BASE_URL + "/api/identity/oauth2/dcr/v1.1/register"

TOKEN_ENDPOINT = BASE_URL + "/oauth2/token"

DCR_CLIENT_ID = "oidc_test_clientid001"

DCR_CLIENT_SECRET = "oidc_test_client_secret001"

APPLICATION_ENDPOINT = BASE_URL + "/api/server/v1/applications"

SCOPES = "internal_user_mgt_update internal_application_mgt_create internal_application_mgt_view internal_login " \
         "internal_claim_meta_update internal_application_mgt_update internal_scope_mgt_create"

DCR_HEADERS = {'Content-Type': 'application/json', 'Connection': 'keep-alive',
               'Authorization': 'Basic YWRtaW46YWRtaW4='}
DCR_BODY = {
    'client_name': 'python_script',
    "grant_types": ["password"],
    "ext_param_client_id": DCR_CLIENT_ID,
    "ext_param_client_secret": DCR_CLIENT_SECRET,
    "is_management_app": True
}

SMTP_SERVER = "smtp.gmail.com"

SMTP_SERVER_PORT = 465
