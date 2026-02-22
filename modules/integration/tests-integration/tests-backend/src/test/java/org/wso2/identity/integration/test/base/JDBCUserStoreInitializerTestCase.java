/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.wso2.identity.integration.test.base;

import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.wso2.carbon.integration.common.utils.mgt.ServerConfigurationManager;
import org.wso2.carbon.utils.CarbonUtils;
import org.wso2.identity.integration.common.utils.ISIntegrationTest;
import org.wso2.identity.integration.common.utils.ISServerConfiguration;
import org.wso2.identity.integration.common.utils.DockerServerConfigurationManager;

import java.io.File;

public class JDBCUserStoreInitializerTestCase extends ISIntegrationTest {

    private ServerConfigurationManager scm;
    private DockerServerConfigurationManager dockerScm;
    private File defaultConfigFile;

    @BeforeTest(alwaysRun = true)
    public void initUserStoreConfig() throws Exception {

        super.init();
        File userMgtConfigFile = new File(getISResourceLocation() + File.separator + "userMgt"
                + File.separator + "jdbc_user_mgt_config.toml");

        if (ISServerConfiguration.isDockerMode()) {
            dockerScm = new DockerServerConfigurationManager();
            dockerScm.applyConfiguration(userMgtConfigFile);
            super.init();
        } else {
            String carbonHome = CarbonUtils.getCarbonHome();
            defaultConfigFile = getDeploymentTomlFile(carbonHome);
            scm = new ServerConfigurationManager(isServer);
            scm.applyConfiguration(userMgtConfigFile, defaultConfigFile, true, true);
        }
    }

    @AfterTest(alwaysRun = true)
    public void resetUserstoreConfig() throws Exception {

        if (ISServerConfiguration.isDockerMode()) {
            dockerScm.restoreToLastConfiguration(true);
            super.init();
        } else {
            super.init();
            scm.restoreToLastConfiguration(false);
        }
    }

}
