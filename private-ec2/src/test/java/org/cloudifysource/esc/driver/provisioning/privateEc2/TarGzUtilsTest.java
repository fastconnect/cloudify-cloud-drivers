/*******************************************************************************
 * Copyright (c) 2013 GigaSpaces Technologies Ltd. All rights reserved
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 ******************************************************************************/
package org.cloudifysource.esc.driver.provisioning.privateEc2;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.junit.Assert;
import org.junit.Test;

public class TarGzUtilsTest {

	@Test
	public void testCreateTarGz() throws Exception {
		File targzfile = TarGzUtils.createTarGz(
				"C:/cloudify-deployment/gigaspaces-cloudify-2.6.1-ga-b5199-139/clouds/privateEc2",
				false);
		System.out.println(targzfile);
	}

	@Test
	public void testCreateTarGzWithMultipleFolder() throws Exception {
		File createTempFile = File.createTempFile("test", "");
		createTempFile.delete();
		File tmpFile = new File(createTempFile, "setenv.sh");
		tmpFile.deleteOnExit();
		FileUtils.writeStringToFile(tmpFile, "this is a test");

		File createTarGz = TarGzUtils.createTarGz(
				new String[] { "C:/cloudify-deployment/gigaspaces-cloudify-2.6.1-ga-b5199-139/clouds/privateEc2",
						tmpFile.getAbsolutePath() }, false);

		File destinationFolder = File.createTempFile("test", "");
		destinationFolder.delete();
		destinationFolder.mkdirs();
		destinationFolder.deleteOnExit();

		TarGzUtils.extract(createTarGz, destinationFolder.getAbsolutePath());

		List<String> filenames = new ArrayList<String>();
		for (File file : destinationFolder.listFiles()) {
			filenames.add(file.getName());
		}
		Assert.assertTrue(filenames.contains("setenv.sh"));
		Assert.assertTrue(filenames.contains("upload"));
		Assert.assertTrue(filenames.contains("privateEc2-cloud.groovy"));
	}
}
