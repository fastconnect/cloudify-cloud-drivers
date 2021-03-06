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
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.StringUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.CharEncoding;
import org.cloudifysource.dsl.cloud.Cloud;
import org.cloudifysource.dsl.cloud.CloudUser;
import org.cloudifysource.dsl.cloud.ScriptLanguages;
import org.cloudifysource.dsl.cloud.compute.ComputeTemplate;
import org.cloudifysource.esc.driver.provisioning.CloudDriverSupport;
import org.cloudifysource.esc.driver.provisioning.CloudProvisioningException;
import org.cloudifysource.esc.driver.provisioning.CustomServiceDataAware;
import org.cloudifysource.esc.driver.provisioning.MachineDetails;
import org.cloudifysource.esc.driver.provisioning.ManagementProvisioningContext;
import org.cloudifysource.esc.driver.provisioning.ProvisioningContext;
import org.cloudifysource.esc.driver.provisioning.ProvisioningContextAccess;
import org.cloudifysource.esc.driver.provisioning.ProvisioningContextImpl;
import org.cloudifysource.esc.driver.provisioning.ProvisioningDriver;
import org.cloudifysource.esc.driver.provisioning.privateEc2.parser.ParserUtils;
import org.cloudifysource.esc.driver.provisioning.privateEc2.parser.PrivateEc2ParserException;
import org.cloudifysource.esc.driver.provisioning.privateEc2.parser.beans.AWSEC2Instance;
import org.cloudifysource.esc.driver.provisioning.privateEc2.parser.beans.AWSEC2Volume;
import org.cloudifysource.esc.driver.provisioning.privateEc2.parser.beans.InstanceProperties;
import org.cloudifysource.esc.driver.provisioning.privateEc2.parser.beans.PrivateEc2Template;
import org.cloudifysource.esc.driver.provisioning.privateEc2.parser.beans.VolumeMapping;
import org.cloudifysource.esc.driver.provisioning.privateEc2.parser.beans.VolumeProperties;
import org.cloudifysource.esc.driver.provisioning.privateEc2.parser.beans.types.ValueType;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Region;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.BlockDeviceMapping;
import com.amazonaws.services.ec2.model.CreateTagsRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.DescribeTagsRequest;
import com.amazonaws.services.ec2.model.DescribeTagsResult;
import com.amazonaws.services.ec2.model.DescribeVolumesRequest;
import com.amazonaws.services.ec2.model.DescribeVolumesResult;
import com.amazonaws.services.ec2.model.EbsBlockDevice;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Placement;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.TagDescription;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.ec2.model.TerminateInstancesResult;
import com.amazonaws.services.ec2.model.Volume;
import com.amazonaws.services.s3.model.S3Object;

/**
 * A custom Cloud Driver to provision Amazon EC2 machines using cloud formation templates.<br />
 * This driver can still start machines the usual way using cloudify groovy templates.
 * 
 * @author victor
 * 
 */
public class PrivateEC2CloudifyDriver extends CloudDriverSupport implements
		ProvisioningDriver, CustomServiceDataAware {

	private static final int DEFAULT_CLOUDIFY_AGENT_PORT = 7002;
	private static final int AMAZON_EXCEPTION_CODE_400 = 400;
	private static final int MAX_SERVERS_LIMIT = 200;
	private static final long WAIT_STATUS_SLEEP_TIME = 5000L;

	private static final String CLOUDIFY_ENV_SCRIPT = "cloudify_env.sh";
	private static final String PATTERN_PROPS_JSON = "\\s*\\\"[\\w-]*\\\"\\s*:\\s*([^{(\\[\"][\\w-]+)\\s*,?";
	private static final String VOLUME_PREFIX = "cloudify-storage-";

	/** Key name for amazon tag resource's name. */
	private static final String TK_NAME = "Name";

	/**
	 * Enumeration for supported 'resource-type' value used in com.amazonaws.services.ec2.model.Filter parameter.
	 */
	private static enum TagResourceType {
		INSTANCE, VOLUME;
		public String getValue() {
			return name().toLowerCase();
		}
	}

	/** Counter for ec2 instances. */
	private static AtomicInteger counter = new AtomicInteger(0);

	/** Counter for storage instances. */
	private static AtomicInteger volumeCounter = new AtomicInteger(0);

	/** Map which contains all parsed CFN template. */
	private final Map<String, PrivateEc2Template> cfnTemplatePerService = new HashMap<String, PrivateEc2Template>();

	private AmazonEC2 ec2;
	private AmazonS3Uploader amazonS3Uploader;

	/** short name of the service (i.e without applicationName). */
	private String serviceName;

	private PrivateEc2Template privateEc2Template;
	private Object cloudTemplateName;
	private String cloudName;

	/**
	 * *****************************************************************************************************************
	 * * ***************************************************************************************************************
	 * ***
	 */

	/**
	 * Sets the custom data file for the cloud driver instance of a specific service.<br />
	 * <p>
	 * customDataFile is a simple file or a folder like the following:
	 * 
	 * <pre>
	 *   -- applicationNameFolder --- serviceName1-cfn.template
	 *                             |- serviceName2-cfn.template
	 *                             |- serviceName3-cfn.template
	 * </pre>
	 * 
	 * </p>
	 * 
	 * @param customDataFile
	 *            files or directory containing the amazon cloudformation template of a specific service
	 * 
	 */
	@Override
	public void setCustomDataFile(final File customDataFile) {
		logger.info("Received custom data file: " + customDataFile);

		final Map<String, PrivateEc2Template> map = new HashMap<String, PrivateEc2Template>();
		PrivateEc2Template mapJson = null;

		try {
			if (customDataFile.isFile()) {
				String templateName = this.getTemplatName(customDataFile);
				logger.fine("Parsing CFN Template for service=" + templateName);
				mapJson = ParserUtils.mapJson(PrivateEc2Template.class, customDataFile);
				map.put(templateName, mapJson);
			} else {
				File[] listFiles = customDataFile.listFiles();
				if (listFiles != null) {
					for (File file : listFiles) {
						if (this.isTemplateFile(file)) {
							String templateName = this.getTemplatName(file);
							logger.fine("Parsing CFN Template for service=" + templateName);

							File pFile = this.getPropertiesFileIfExists(templateName, customDataFile.listFiles());
							if (pFile != null) {
								// Replace properties variable with values if the properties file exists
								String templateString = this.replaceProperties(file, pFile);
								mapJson = ParserUtils.mapJson(PrivateEc2Template.class, templateString);
								map.put(templateName, mapJson);

							} else {
								mapJson = ParserUtils.mapJson(PrivateEc2Template.class, file);
								map.put(templateName, mapJson);
							}
						}
					}
				}
			}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Couldn't parse the template file: " + customDataFile.getPath());
			throw new IllegalStateException(e);
		}
		this.cfnTemplatePerService.putAll(map);
	}

	private String replaceProperties(final File file, final File propertiesFile) throws IOException {
		logger.fine("Properties file=" + propertiesFile.getName());
		Properties props = new Properties();
		props.load(new FileInputStream(propertiesFile));

		String templateString = FileUtils.readFileToString(file);

		Pattern p = Pattern.compile(PATTERN_PROPS_JSON);
		Matcher m = p.matcher(templateString);
		while (m.find()) {
			String group = m.group();
			String group1 = m.group(1);
			if (props.containsKey(group1)) {
				String value = props.getProperty(group1);
				if (logger.isLoggable(Level.FINEST)) {
					logger.finest("Replacing property " + group + " by " + value);
				}
				templateString = m.replaceFirst(group.replace(group1, value));
				m = p.matcher(templateString);
			} else {
				throw new IllegalStateException("Couldn't find property: " + group1);
			}
		}
		return templateString;
	}

	private File getPropertiesFileIfExists(final String templateName, final File[] listFiles) {
		String filename = templateName + "-cfn.properties";
		for (File file : listFiles) {
			if (filename.equals(file.getName())) {
				return file;
			}
		}
		return null;
	}

	private String getTemplatName(final File file) {
		String name = file.getName();
		name = name.replace("-cfn.template", "");
		return name;
	}

	private boolean isTemplateFile(final File file) {
		String name = file.getName();
		return name.endsWith("-cfn.template");
	}

	/** Testing purpose. */
	PrivateEc2Template getCFNTemplatePerService(final String serviceName) {
		return cfnTemplatePerService.get(serviceName);
	}

	public Cloud getCloud() {
		return this.cloud;
	}

	/**
	 * *****************************************************************************************************************
	 */

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.cloudifysource.esc.driver.provisioning.BaseProvisioningDriver#setConfig(org.cloudifysource.dsl.cloud.Cloud,
	 * java.lang.String, boolean, java.lang.String)
	 */
	@Override
	public void setConfig(final Cloud cloud, final String cloudTemplateName, final boolean management,
			final String fullServiceName) {

		logger.fine("Running path : " + System.getProperty("user.dir"));

		this.serviceName = this.getSimpleServiceName(fullServiceName);
		this.cloudTemplateName = cloudTemplateName;
		this.cloudName = cloud.getName();
		super.setConfig(cloud, cloudTemplateName, management, fullServiceName);

		if (logger.isLoggable(Level.FINER)) {
			logger.finer("Service name : " + this.serviceName + "(" + fullServiceName + ")");
		}

		try {
			ComputeTemplate managerTemplate = this.getManagerComputeTemplate();

			// Initialize the ec2 client if the service use the CFN template
			if (management) {
				String managerCfnTemplateFile = (String) managerTemplate.getCustom().get("cfnManagerTemplate");
				this.privateEc2Template = this.getManagerPrivateEc2Template(managerCfnTemplateFile);
			} else {
				this.privateEc2Template = cfnTemplatePerService.get(this.serviceName);
				if (this.privateEc2Template == null) {
					throw new IllegalArgumentException("CFN template not found for service:" + fullServiceName);
				}
			}
			this.ec2 = this.createAmazonEC2();

			// Create s3 client
			String locationId = (String) managerTemplate.getCustom().get("s3LocationId");
			CloudUser user = this.cloud.getUser();
			this.amazonS3Uploader = new AmazonS3Uploader(user.getUser(), user.getApiKey(), locationId);

		} catch (CloudProvisioningException e) {
			throw new IllegalArgumentException(e);
		} catch (PrivateEc2ParserException e) {
			throw new IllegalArgumentException(e);
		} catch (IOException e) {
			throw new IllegalArgumentException(e);
		}

	}

	private ComputeTemplate getManagerComputeTemplate() {
		String managementMachineTemplate = this.cloud.getConfiguration().getManagementMachineTemplate();
		ComputeTemplate managerTemplate =
				this.cloud.getCloudCompute().getTemplates().get(managementMachineTemplate);
		return managerTemplate;
	}

	PrivateEc2Template getManagerPrivateEc2Template(final String managerCfnTemplateFile)
			throws PrivateEc2ParserException, IOException {
		File file = new File(managerCfnTemplateFile);

		if (!file.exists()) {
			throw new IllegalArgumentException("CFN Template not found: " + file.getPath());
		}

		logger.fine("Manager cfn template: " + file.getPath());

		String templateName = this.getTemplatName(file);
		File pFile = new File(file.getParent(), templateName + "-cfn.properties");

		logger.fine("Searching for manager cfn properties: " + file.getPath());

		PrivateEc2Template mapJson = null;
		if (pFile.exists()) {
			// Replace properties variable with values if the properties file exists
			String templateString = this.replaceProperties(file, pFile);
			logger.fine("The template:\n" + templateString);
			mapJson = ParserUtils.mapJson(PrivateEc2Template.class, templateString);
		} else {
			mapJson = ParserUtils.mapJson(PrivateEc2Template.class, file);
		}
		return mapJson;
	}

	/**
	 * Remove application name from the string.<br />
	 * i.e. if fullServiceName = sampleApplication.someService, it will return someService.
	 * 
	 * @param fullServiceName
	 *            A service name.
	 * @return The service name shortened by the application name.
	 */
	private String getSimpleServiceName(final String fullServiceName) {
		if (fullServiceName != null && fullServiceName.contains(".")) {
			return fullServiceName.substring(fullServiceName.lastIndexOf(".") + 1, fullServiceName.length());
		}
		return fullServiceName;
	}

	private AmazonEC2 createAmazonEC2() throws CloudProvisioningException {
		CloudUser user = cloud.getUser();
		AWSCredentials credentials = new BasicAWSCredentials(user.getUser(), user.getApiKey());

		AmazonEC2 ec2 = new AmazonEC2Client(credentials);

		String endpoint = (String) cloud.getCustom().get("endpoint");
		if (endpoint != null) {
			ec2.setEndpoint(endpoint);
		} else {
			Region region = this.getRegion();
			ec2.setRegion(region);
		}
		return ec2;
	}

	private Region getRegion() throws CloudProvisioningException {
		AWSEC2Instance instance = this.privateEc2Template.getEC2Instance();
		ValueType availabilityZoneObj = instance.getProperties().getAvailabilityZone();
		if (availabilityZoneObj != null) {
			Region region = RegionUtils.convertAvailabilityZone2Region(availabilityZoneObj.getValue());
			logger.info("Amazon ec2 region: " + region);
			return region;
		}
		throw new CloudProvisioningException("Region and/or endpoint aren't defined");
	}

	/**
	 * *****************************************************************************************************************
	 */

	/**
	 * Start machines using CFN template if provides, use JClouds otherwise.
	 * 
	 * @param locationId
	 *            the location to allocate the machine to.
	 * @param duration
	 *            Time duration to wait for the instance.
	 * @param unit
	 *            Time unit to wait for the instance.
	 * 
	 * @return The details of the started instance.
	 * 
	 * @throws TimeoutException
	 *             In case the instance was not started in the allotted time.
	 * @throws CloudProvisioningException
	 *             If a problem was encountered while starting the machine.
	 */
	@Override
	public MachineDetails startMachine(final String locationId, final long duration, final TimeUnit unit)
			throws TimeoutException, CloudProvisioningException {
		if (logger.isLoggable(Level.FINEST)) {
			logger.finest("Stating new machine with the following thread: threadId=" + Thread.currentThread().getId()
					+ " serviceName=" + this.serviceName);
		}

		String newName = this.createNewName(TagResourceType.INSTANCE, cloud.getProvider().getMachineNamePrefix());
		ProvisioningContextImpl ctx =
				(ProvisioningContextImpl) new ProvisioningContextAccess().getProvisioiningContext();
		MachineDetails md = this.createServer(this.privateEc2Template, newName, ctx, false, duration, unit);

		logger.fine("[" + md.getMachineId() + "] Cloud Server is allocated.");
		return md;
	}

	@Override
	public boolean stopMachine(final String serverIp, final long duration, final TimeUnit unit)
			throws CloudProvisioningException,
			TimeoutException, InterruptedException {
		if (logger.isLoggable(Level.FINEST)) {
			logger.finest("Stopping new machine with the following thread: threadId=" + Thread.currentThread().getId()
					+ " serviceName=" + this.serviceName
					+ " serverIp=" + serverIp);
		}

		logger.info("Stopping instance server ip = " + serverIp + "...");
		DescribeInstancesRequest describeInstance = new DescribeInstancesRequest();
		describeInstance.withFilters(new Filter("private-ip-address", Arrays.asList(serverIp)));
		DescribeInstancesResult describeInstances = ec2.describeInstances(describeInstance);

		Reservation reservation = describeInstances.getReservations().get(0);
		if (reservation != null && reservation.getInstances().get(0) != null) {
			TerminateInstancesRequest tir = new TerminateInstancesRequest();
			tir.withInstanceIds(reservation.getInstances().get(0).getInstanceId());
			TerminateInstancesResult terminateInstances = ec2.terminateInstances(tir);

			String instanceId = terminateInstances.getTerminatingInstances().get(0).getInstanceId();

			try {
				this.waitStopInstanceStatus(instanceId, duration, unit);
			} finally {
				// FIXME By default, cloudify doesn't delete tags. So we should keep it that way.
				// Remove instance Tags
				// if (!terminateInstances.getTerminatingInstances().isEmpty()) {
				// logger.fine("Deleting tags for instance id=" + instanceId);
				// DeleteTagsRequest deleteTagsRequest = new DeleteTagsRequest();
				// deleteTagsRequest.setResources(Arrays.asList(instanceId));
				// ec2.deleteTags(deleteTagsRequest);
				// }
			}

		} else {
			logger.warning("No instance to stop: " + reservation);
		}
		return true;
	}

	private void waitStopInstanceStatus(final String instanceId, final long duration, final TimeUnit unit)
			throws CloudProvisioningException, TimeoutException {
		long endTime = System.currentTimeMillis() + unit.toMillis(duration);
		while (System.currentTimeMillis() < endTime) {

			DescribeInstancesRequest describeRequest = new DescribeInstancesRequest();
			describeRequest.withInstanceIds(instanceId);
			DescribeInstancesResult describeInstances = ec2.describeInstances(describeRequest);

			for (Reservation resa : describeInstances.getReservations()) {
				for (Instance instance : resa.getInstances()) {
					InstanceStateType state = InstanceStateType.valueOf(instance.getState().getCode());
					if (logger.isLoggable(Level.FINEST)) {
						logger.finest("instance= " + instance.getInstanceId() + " state=" + state);
					}
					switch (state) {
					case PENDING:
					case RUNNING:
					case STOPPING:
					case SHUTTING_DOWN:
						this.sleep();
						break;
					case STOPPED:
					case TERMINATED:
						if (logger.isLoggable(Level.FINEST)) {
							logger.finest("instance (id=" + instanceId + ") was shutdown");
						}
						return;
					default:
						throw new CloudProvisioningException("Failed to stop server - Cloud reported node in "
								+ state.getName() + " state.");

					}

				}
			}
		}

		throw new TimeoutException("Stopping instace timed out (id=" + instanceId + ")");
	}

	private void sleep() {
		try {
			if (logger.isLoggable(Level.FINEST)) {
				logger.finest("sleeping...");
			}
			Thread.sleep(WAIT_STATUS_SLEEP_TIME);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private MachineDetails createServer(final PrivateEc2Template cfnTemplate, final String machineName,
			final ProvisioningContextImpl ctx, final boolean management, final long duration, final TimeUnit unit)
			throws CloudProvisioningException, TimeoutException {
		Instance ec2Instance = this.createEC2Instance(cfnTemplate, ctx, management, machineName, duration, unit);

		MachineDetails md = new MachineDetails();
		md.setMachineId(ec2Instance.getInstanceId());
		md.setPrivateAddress(ec2Instance.getPrivateIpAddress());
		md.setPublicAddress(ec2Instance.getPublicIpAddress());
		md.setAgentRunning(true);
		md.setCloudifyInstalled(true);
		return md;
	}

	private void tagEC2Instance(final Instance ec2Instance, final String ec2InstanceName,
			final AWSEC2Instance templateInstance)
			throws CloudProvisioningException {
		List<Tag> additionalTags = Arrays.asList(new Tag(TK_NAME, ec2InstanceName));
		this.createEC2Tags(ec2Instance.getInstanceId(), templateInstance.getProperties().getTags(), additionalTags);
	}

	private void tagEC2Volumes(final String instanceId, final PrivateEc2Template cfnTemplate)
			throws CloudProvisioningException {

		List<VolumeMapping> volumeMappings = cfnTemplate.getEC2Instance().getProperties().getVolumes();
		if (volumeMappings != null) {
			DescribeVolumesRequest request = new DescribeVolumesRequest();
			request.withFilters(new Filter("attachment.instance-id", Arrays.asList(instanceId)));
			DescribeVolumesResult describeVolumes = ec2.describeVolumes(request);

			for (Volume volume : describeVolumes.getVolumes()) {
				String volumeRef = null;
				for (VolumeMapping vMap : volumeMappings) {
					String device = volume.getAttachments().get(0).getDevice();
					if (device.equals(vMap.getDevice().getValue())) {
						volumeRef = vMap.getVolumeId().getValue();
						break;
					}
				}
				if (volumeRef != null) {
					AWSEC2Volume ec2Volume = cfnTemplate.getEC2Volume(volumeRef);
					List<org.cloudifysource.esc.driver.provisioning.privateEc2.parser.beans.Tag> templateTags =
							ec2Volume == null ? null : ec2Volume
									.getProperties().getTags();
					List<Tag> additionalTags =
							Arrays.asList(new Tag(TK_NAME, this.createNewName(TagResourceType.VOLUME, VOLUME_PREFIX)));
					this.createEC2Tags(volume.getVolumeId(), templateTags, additionalTags);
				}
			}
		}
	}

	private String createNewName(final TagResourceType resourceType, final String prefix)
			throws CloudProvisioningException {
		String newName = null;
		int attempts = 0;
		boolean foundFreeName = false;

		while (attempts < MAX_SERVERS_LIMIT) {
			// counter = (counter + 1) % MAX_SERVERS_LIMIT;
			++attempts;

			switch (resourceType) {
			case INSTANCE:
				newName = prefix + counter.incrementAndGet();
				break;
			case VOLUME:
				newName = prefix + volumeCounter.incrementAndGet();
				break;
			default:
				// not possible
				throw new CloudProvisioningException("ResourceType not supported");
			}

			// verifying this server name is not already used
			DescribeTagsRequest tagRequest = new DescribeTagsRequest();
			tagRequest.withFilters(new Filter("resource-type", Arrays.asList(resourceType.getValue())));
			tagRequest.withFilters(new Filter("value", Arrays.asList(newName)));
			DescribeTagsResult describeTags = ec2.describeTags(tagRequest);
			List<TagDescription> tags = describeTags.getTags();
			if (tags == null || tags.isEmpty()) {
				foundFreeName = true;
				break;
			}
		}

		if (!foundFreeName) {
			throw new CloudProvisioningException("Number of servers has exceeded allowed server limit ("
					+ MAX_SERVERS_LIMIT + ")");
		}
		return newName;
	}

	private void createEC2Tags(final String resourceId,
			final List<org.cloudifysource.esc.driver.provisioning.privateEc2.parser.beans.Tag> templateTags,
			final List<Tag> additionalTags) {
		List<Tag> tags = new ArrayList<Tag>();

		if (templateTags != null) {
			for (org.cloudifysource.esc.driver.provisioning.privateEc2.parser.beans.Tag tag : templateTags) {
				tags.add(tag.convertToEC2Model());
			}
		}

		if (additionalTags != null) {
			tags.addAll(additionalTags);
		}

		if (!tags.isEmpty()) {
			logger.fine("Tag resourceId=" + resourceId + " tags=" + tags);
			CreateTagsRequest ctr = new CreateTagsRequest();
			ctr.setTags(tags);
			ctr.withResources(resourceId);
			this.ec2.createTags(ctr);
		}
	}

	private Instance waitRunningInstance(final Instance ec2instance, final long duration, final TimeUnit unit)
			throws CloudProvisioningException, TimeoutException {
		long endTime = System.currentTimeMillis() + unit.toMillis(duration);

		while (System.currentTimeMillis() < endTime) {
			// Sleep before requesting the instance description
			// because we can get a AWS Error Code: InvalidInstanceID.NotFound if the request is too early.
			this.sleep();

			DescribeInstancesRequest describeRequest = new DescribeInstancesRequest();
			describeRequest.setInstanceIds(Arrays.asList(ec2instance.getInstanceId()));
			DescribeInstancesResult describeInstances = this.ec2.describeInstances(describeRequest);

			for (Reservation resa : describeInstances.getReservations()) {
				for (Instance instance : resa.getInstances()) {
					InstanceStateType state = InstanceStateType.valueOf(instance.getState().getCode());
					if (logger.isLoggable(Level.FINER)) {
						logger.finer("instance= " + instance.getInstanceId() + " state=" + state);
					}
					switch (state) {
					case PENDING:
						break;
					case RUNNING:
						logger.fine("running okay...");
						return instance;
					case STOPPING:
					case SHUTTING_DOWN:
					case TERMINATED:
					case STOPPED:
					default:
						throw new CloudProvisioningException("Failed to allocate server - Cloud reported node in "
								+ state.getName() + " state. Node details: "
								+ ec2instance);
					}

				}
			}
		}

		throw new TimeoutException("Node failed to reach RUNNING mode in time");
	}

	private MachineDetails[] getManagementServersMachineDetails() throws CloudProvisioningException {

		DescribeInstancesResult describeInstances = this.requestEC2InstancesManager();
		if (describeInstances == null) {
			return new MachineDetails[0];
		}
		List<MachineDetails> mds = new ArrayList<MachineDetails>();
		for (Reservation resa : describeInstances.getReservations()) {
			for (Instance instance : resa.getInstances()) {
				MachineDetails md = this.createMachineDetailsFromInstance(instance);
				mds.add(md);
			}
		}

		return mds.toArray(new MachineDetails[mds.size()]);
	}

	private DescribeInstancesResult requestEC2InstancesManager() {
		try {
			DescribeInstancesRequest request = new DescribeInstancesRequest();
			request.withFilters(new Filter("instance-state-name", Arrays.asList(InstanceStateType.RUNNING.getName())),
					new Filter("tag-key", Arrays.asList("Name")),
					new Filter("tag-value", Arrays.asList(cloud.getProvider().getManagementGroup() + "*")));
			DescribeInstancesResult describeInstances = ec2.describeInstances(request);
			return describeInstances;
		} catch (AmazonServiceException e) {
			if (e.getStatusCode() == AMAZON_EXCEPTION_CODE_400) {
				// Not found
				return null;
			} else {
				throw e;
			}
		}
	}

	private MachineDetails createMachineDetailsFromInstance(final Instance instance) throws CloudProvisioningException {
		final ComputeTemplate template = this.cloud.getCloudCompute().getTemplates().get(
				this.cloudTemplateName);

		if (template == null) {
			throw new CloudProvisioningException("Could not find template " + this.cloudTemplateName);
		}

		final MachineDetails md = new MachineDetails();
		md.setAgentRunning(false);
		md.setRemoteExecutionMode(template.getRemoteExecution());
		md.setFileTransferMode(template.getFileTransfer());
		md.setScriptLangeuage(template.getScriptLanguage());

		md.setCloudifyInstalled(false);
		md.setInstallationDirectory(null);
		md.setMachineId(instance.getInstanceId());
		md.setPrivateAddress(instance.getPrivateIpAddress());
		md.setPublicAddress(instance.getPublicIpAddress());
		md.setRemoteUsername(template.getUsername());
		md.setRemotePassword(template.getPassword());
		String availabilityZone = instance.getPlacement().getAvailabilityZone();
		md.setLocationId(RegionUtils.convertAvailabilityZone2LocationId(availabilityZone));

		return md;
	}

	private Instance createEC2Instance(final PrivateEc2Template cfnTemplate, final ProvisioningContextImpl ctx,
			final boolean management, final String machineName, final long duration, final TimeUnit unit)
			throws CloudProvisioningException, TimeoutException {

		final InstanceProperties properties = cfnTemplate.getEC2Instance().getProperties();

		final String availabilityZone = properties.getAvailabilityZone() == null
				? null : properties.getAvailabilityZone().getValue();
		final Placement placement = availabilityZone == null ? null : new Placement(availabilityZone);

		final String imageId = properties.getImageId() == null ? null : properties.getImageId().getValue();
		final String instanceType = properties.getInstanceType() == null
				? null : properties.getInstanceType().getValue();
		final String keyName = properties.getKeyName() == null ? null : properties.getKeyName().getValue();
		final String privateIpAddress = properties.getPrivateIpAddress() == null
				? null : properties.getPrivateIpAddress().getValue();
		final List<String> securityGroupIds = properties.getSecurityGroupIdsAsString();
		final List<String> securityGroups = properties.getSecurityGroupsAsString();

		S3Object s3Object = null;
		try {

			String userData = null;
			if (properties.getUserData() != null) {
				// Generate ENV script for the provisioned machine
				final StringBuilder sb = new StringBuilder();
				final String script =
						management ? this.generateManagementCloudifyEnv(ctx) : this.generateCloudifyEnv(ctx);

				s3Object = this.uploadCloudDir(ctx, script, management);
				final String cloudFileS3 = this.amazonS3Uploader.generatePresignedURL(s3Object);

				ComputeTemplate template = this.getManagerComputeTemplate();
				String cloudFileDir = (String) template.getRemoteDirectory();
				// Remove '/' from the path if it's the last char.
				if (cloudFileDir.length() > 1 && cloudFileDir.endsWith("/")) {
					cloudFileDir = cloudFileDir.substring(0, cloudFileDir.length() - 1);
				}
				String endOfLine = " >> /tmp/cloud.txt\n";
				sb.append("#!/bin/bash\n");
				sb.append("export TMP_DIRECTORY=/tmp").append(endOfLine);
				sb.append("export S3_ARCHIVE_FILE='" + cloudFileS3 + "'").append(endOfLine);
				sb.append("wget -q -O $TMP_DIRECTORY/cloudArchive.tar.gz $S3_ARCHIVE_FILE").append(endOfLine);
				sb.append("mkdir -p " + cloudFileDir).append(endOfLine);
				sb.append("tar zxvf $TMP_DIRECTORY/cloudArchive.tar.gz -C " + cloudFileDir).append(endOfLine);
				sb.append("rm -f $TMP_DIRECTORY/cloudArchive.tar.gz").append(endOfLine);
				sb.append("echo ").append(cloudFileDir).append("/").append(CLOUDIFY_ENV_SCRIPT).append(endOfLine);
				sb.append("chmod 755 ").append(cloudFileDir).append("/").append(CLOUDIFY_ENV_SCRIPT).append(endOfLine);
				sb.append("source ").append(cloudFileDir).append("/").append(CLOUDIFY_ENV_SCRIPT).append(endOfLine);

				sb.append(properties.getUserData().getValue());
				userData = sb.toString();
				logger.fine("Instanciate ec2 with user data:\n" + userData);
				userData = StringUtils.newStringUtf8(Base64.encodeBase64(userData.getBytes()));
			}

			List<BlockDeviceMapping> blockDeviceMappings = null;
			AWSEC2Volume volumeConfig = null;
			if (properties.getVolumes() != null) {
				blockDeviceMappings = new ArrayList<BlockDeviceMapping>(properties.getVolumes().size());
				for (final VolumeMapping volMapping : properties.getVolumes()) {
					volumeConfig = cfnTemplate.getEC2Volume(volMapping.getVolumeId().getValue());
					blockDeviceMappings.add(this.createBlockDeviceMapping(volMapping.getDevice().getValue(),
							volumeConfig));
				}
			}

			final RunInstancesRequest runInstancesRequest = new RunInstancesRequest();
			runInstancesRequest.withPlacement(placement);
			runInstancesRequest.withImageId(imageId);
			runInstancesRequest.withInstanceType(instanceType);
			runInstancesRequest.withKeyName(keyName);
			runInstancesRequest.withPrivateIpAddress(privateIpAddress);
			runInstancesRequest.withSecurityGroupIds(securityGroupIds);
			runInstancesRequest.withSecurityGroups(securityGroups);
			runInstancesRequest.withMinCount(1);
			runInstancesRequest.withMaxCount(1);
			runInstancesRequest.withBlockDeviceMappings(blockDeviceMappings);
			runInstancesRequest.withUserData(userData);

			if (logger.isLoggable(Level.FINEST)) {
				logger.finest("EC2::Instance request=" + runInstancesRequest);
			}

			final RunInstancesResult runInstances = this.ec2.runInstances(runInstancesRequest);
			if (runInstances.getReservation().getInstances().size() != 1) {
				throw new CloudProvisioningException("Request runInstace fails (request=" + runInstancesRequest + ").");
			}

			Instance ec2Instance = runInstances.getReservation().getInstances().get(0);
			ec2Instance = this.waitRunningInstance(ec2Instance, duration, unit);
			this.tagEC2Instance(ec2Instance, machineName, cfnTemplate.getEC2Instance());
			this.tagEC2Volumes(ec2Instance.getInstanceId(), cfnTemplate);
			this.waitRunningAgent(ec2Instance.getPublicIpAddress(), duration, unit);

			return ec2Instance;
		} finally {
			if (s3Object != null) {
				this.amazonS3Uploader.deleteS3Object(s3Object.getBucketName(), s3Object.getKey());
			}
		}
	}

	private void waitRunningAgent(final String host, final long duration, final TimeUnit unit) {
		long endTime = System.currentTimeMillis() + unit.toMillis(duration);
		Socket socket = null;
		while (System.currentTimeMillis() < endTime) {
			try {
				socket = new Socket(host, DEFAULT_CLOUDIFY_AGENT_PORT);
				logger.fine("Agent is reachable on: " + host + ":" + DEFAULT_CLOUDIFY_AGENT_PORT);
				break;
			} catch (Exception e) {
				this.sleep();
			} finally {
				if (socket != null) {
					try {
						socket.close();
					} catch (IOException e) {
						continue;
					}
				}
			}
		}

	}

	private S3Object uploadCloudDir(final ProvisioningContextImpl ctx, final String script, final boolean isManagement)
			throws CloudProvisioningException {
		try {
			final ComputeTemplate template = this.getManagerComputeTemplate();
			final String cloudDirectory = isManagement ? (String) template.getCustom().get("cloudDirectory")
					: template.getAbsoluteUploadDir();
			final String s3BucketName = (String) template.getCustom().get("s3BucketName");

			// Generate env script
			final StringBuilder sb = new StringBuilder();
			sb.append("#!/bin/bash\n");
			sb.append(script);
			if (isManagement) {
				// TODO retrieve port dynamically for LUS_IP_ADDRESS
				sb.append("export LUS_IP_ADDRESS=`curl http://instance-data/latest/meta-data/local-ipv4`:4174");
			}

			// Create tmp dir
			final File createTempFile = File.createTempFile("cloudify_env", "");
			createTempFile.delete();
			// Create tmp file
			final File tmpEnvFile = new File(createTempFile, CLOUDIFY_ENV_SCRIPT);
			tmpEnvFile.deleteOnExit();
			// Write the script into the temp filedir
			FileUtils.writeStringToFile(tmpEnvFile, sb.toString(), CharEncoding.UTF_8);

			// Compress file
			logger.fine("Archive folders to upload: " + cloudDirectory + " and " + tmpEnvFile.getAbsolutePath());
			String[] sourcePaths = new String[] { cloudDirectory, tmpEnvFile.getAbsolutePath() };
			final File tarGzFile = TarGzUtils.createTarGz(sourcePaths, false);

			// Upload to S3
			final S3Object s3Object = amazonS3Uploader.uploadFile(s3BucketName, tarGzFile);
			return s3Object;
		} catch (IOException e) {
			throw new CloudProvisioningException(e);
		}
	}

	private BlockDeviceMapping createBlockDeviceMapping(final String device, final AWSEC2Volume volumeConfig)
			throws CloudProvisioningException {
		VolumeProperties volumeProperties = volumeConfig.getProperties();
		Integer iops = volumeProperties.getIops() == null ? null : volumeProperties.getIops();
		Integer size = volumeProperties.getSize();
		String snapshotId =
				volumeProperties.getSnapshotId() == null ? null : volumeProperties.getSnapshotId().getValue();
		String volumeType =
				volumeProperties.getVolumeType() == null ? null : volumeProperties.getVolumeType().getValue();

		EbsBlockDevice ebs = new EbsBlockDevice();
		ebs.setIops(iops);
		ebs.setSnapshotId(snapshotId);
		ebs.setVolumeSize(size);
		ebs.setVolumeType(volumeType);
		ebs.setDeleteOnTermination(true);

		BlockDeviceMapping mapping = new BlockDeviceMapping();
		mapping.setDeviceName(device);
		mapping.setEbs(ebs);
		return mapping;
	}

	private String generateManagementCloudifyEnv(final ManagementProvisioningContext ctx)
			throws CloudProvisioningException {
		ComputeTemplate template = new ComputeTemplate();
		// FIXME may not work on windows because of script language
		template.setScriptLanguage(ScriptLanguages.LINUX_SHELL);
		template.setRemoteDirectory("");
		try {
			MachineDetails machineDetails = new MachineDetails();
			machineDetails.setRemoteDirectory(getManagerComputeTemplate().getRemoteDirectory());
			MachineDetails[] mds = { machineDetails };
			// As every specific environment variables will be set with user data we don't need to generate a script per
			// management machine.
			String[] scripts = ctx.createManagementEnvironmentScript(mds, template);
			return scripts[0];
		} catch (FileNotFoundException e) {
			logger.log(Level.SEVERE, "Couldn't find file: ", e.getMessage());
			throw new CloudProvisioningException(e);
		}
	}

	private String generateCloudifyEnv(final ProvisioningContext ctx) throws CloudProvisioningException {
		ComputeTemplate template = new ComputeTemplate();
		// FIXME may not work on windows because of script language
		template.setScriptLanguage(ScriptLanguages.LINUX_SHELL);
		try {
			MachineDetails md = new MachineDetails();
			// TODO set location id in user data.
			md.setLocationId(this.getManagementServersMachineDetails()[0].getLocationId());
			String script = ctx.createEnvironmentScript(md, template);
			return script;
		} catch (FileNotFoundException e) {
			logger.log(Level.SEVERE, "Couldn't find file: ", e.getMessage());
			throw new CloudProvisioningException(e);
		}
	}

	@Override
	public Object getComputeContext() {
		return null;
	}

	@Override
	public MachineDetails[] startManagementMachines(final long duration, final TimeUnit unit) throws TimeoutException,
			CloudProvisioningException {

		if (duration < 0) {
			throw new TimeoutException("Starting a new machine timed out");
		}

		final long endTime = System.currentTimeMillis() + unit.toMillis(duration);

		logger.fine("DefaultCloudProvisioning: startMachine - management == " + management);

		final String managementMachinePrefix = this.cloud.getProvider().getManagementGroup();
		if (org.apache.commons.lang.StringUtils.isBlank(managementMachinePrefix)) {
			throw new CloudProvisioningException(
					"The management group name is missing - can't locate existing servers!");
		}

		// first check if management already exists
		final MachineDetails[] existingManagementServers = this.getManagementServersMachineDetails();
		if (existingManagementServers.length > 0) {
			final String serverDescriptions =
					this.createExistingServersDescription(managementMachinePrefix, existingManagementServers);
			throw new CloudProvisioningException("Found existing servers matching group "
					+ managementMachinePrefix + ": " + serverDescriptions);

		}

		// launch the management machines
		final int numberOfManagementMachines = this.cloud.getProvider().getNumberOfManagementMachines();
		MachineDetails[] createdMachines;
		try {
			createdMachines = this.doStartManagementMachines(numberOfManagementMachines,
					endTime, unit);
		} catch (PrivateEc2ParserException e) {
			throw new CloudProvisioningException(e);
		}
		return createdMachines;
	}

	private String createExistingServersDescription(final String managementMachinePrefix,
			final MachineDetails[] existingManagementServers) {

		logger.info("Found existing servers matching the name: " + managementMachinePrefix);
		final StringBuilder sb = new StringBuilder();
		boolean first = true;
		for (final MachineDetails machineDetails : existingManagementServers) {
			final String existingManagementServerDescription = createManagementServerDescription(machineDetails);
			if (first) {
				first = false;
			} else {
				sb.append(", ");
			}
			sb.append("[").append(existingManagementServerDescription).append("]");
		}
		final String serverDescriptions = sb.toString();
		return serverDescriptions;
	}

	private String createManagementServerDescription(final MachineDetails machineDetails) {
		final StringBuilder sb = new StringBuilder();
		sb.append("Machine ID: ").append(machineDetails.getMachineId());
		if (machineDetails.getPublicAddress() != null) {
			sb.append(", Public IP: ").append(machineDetails.getPublicAddress());
		}
		if (machineDetails.getPrivateAddress() != null) {
			sb.append(", Private IP: ").append(machineDetails.getPrivateAddress());
		}
		return sb.toString();
	}

	private MachineDetails[] doStartManagementMachines(final int numberOfManagementMachines, final long endTime,
			final TimeUnit unit) throws TimeoutException, CloudProvisioningException, PrivateEc2ParserException {
		final ExecutorService executors = Executors.newFixedThreadPool(numberOfManagementMachines);

		@SuppressWarnings("unchecked")
		final Future<MachineDetails>[] futures = (Future<MachineDetails>[]) new Future<?>[numberOfManagementMachines];

		try {
			final PrivateEc2Template template = this.privateEc2Template;
			final String managementGroup = this.cloud.getProvider().getManagementGroup();
			final ProvisioningContextImpl ctx =
					(ProvisioningContextImpl) new ProvisioningContextAccess().getManagementProvisioiningContext();

			logger.info("ctx_threadlocal=" + ctx);

			// Call startMachine asynchronously once for each management machine
			for (int i = 0; i < numberOfManagementMachines; i++) {
				final int index = i + 1;
				futures[i] = executors.submit(new Callable<MachineDetails>() {
					@Override
					public MachineDetails call() throws Exception {
						return createServer(template, managementGroup + index, ctx, true, endTime, unit);
					}
				});

			}

			// Wait for each of the async calls to terminate.
			int numberOfErrors = 0;
			Exception firstCreationException = null;
			final MachineDetails[] createdManagementMachines = new MachineDetails[numberOfManagementMachines];
			for (int i = 0; i < createdManagementMachines.length; i++) {
				try {
					createdManagementMachines[i] = futures[i].get(endTime - System.currentTimeMillis(),
							TimeUnit.MILLISECONDS);
				} catch (final InterruptedException e) {
					++numberOfErrors;
					logger.log(Level.SEVERE, "Failed to start a management machine", e);
					if (firstCreationException == null) {
						firstCreationException = e;
					}

				} catch (final ExecutionException e) {
					++numberOfErrors;
					logger.log(Level.SEVERE, "Failed to start a management machine", e);
					if (firstCreationException == null) {
						firstCreationException = e;
					}
				}
			}

			// In case of a partial error, shutdown all servers that did start up
			if (numberOfErrors > 0) {
				this.handleProvisioningFailure(numberOfManagementMachines, numberOfErrors, firstCreationException,
						createdManagementMachines);
			}

			return createdManagementMachines;
		} finally {
			if (executors != null) {
				executors.shutdownNow();
			}
		}
	}

	private void handleProvisioningFailure(final int numberOfManagementMachines, final int numberOfErrors,
			final Exception firstCreationException, final MachineDetails[] createdManagementMachines)
			throws CloudProvisioningException {
		logger.severe("Of the required " + numberOfManagementMachines
				+ " management machines, " + numberOfErrors
				+ " failed to start.");
		if (numberOfManagementMachines > numberOfErrors) {
			logger.severe("Shutting down the other managememnt machines");

			for (final MachineDetails machineDetails : createdManagementMachines) {
				if (machineDetails != null) {
					logger.severe("Shutting down machine: " + machineDetails);
					TerminateInstancesRequest terminateInstancesRequest = new TerminateInstancesRequest();
					terminateInstancesRequest.setInstanceIds(Arrays.asList(machineDetails.getMachineId()));
					ec2.terminateInstances(terminateInstancesRequest);
				}
			}
		}

		throw new CloudProvisioningException(
				"One or more managememnt machines failed. The first encountered error was: "
						+ firstCreationException.getMessage(),
				firstCreationException);
	}

	@Override
	public void stopManagementMachines() throws TimeoutException, CloudProvisioningException {
		MachineDetails[] managementServersMachineDetails = this.getManagementServersMachineDetails();
		List<String> ids = new ArrayList<String>(managementServersMachineDetails.length);
		for (MachineDetails machineDetails : managementServersMachineDetails) {
			ids.add(machineDetails.getMachineId());
		}
		TerminateInstancesRequest terminateInstancesRequest = new TerminateInstancesRequest();
		terminateInstancesRequest.setInstanceIds(ids);

		logger.info("Terminating management instances... " + terminateInstancesRequest);
		ec2.terminateInstances(terminateInstancesRequest);
	}

	@Override
	public String getCloudName() {
		return this.cloudName;
	}

	@Override
	public void close() {
		if (ec2 != null) {
			ec2.shutdown();
		}
	}

	@Override
	public void onServiceUninstalled(final long duration, final TimeUnit unit) throws InterruptedException,
			TimeoutException,
			CloudProvisioningException {
		this.cfnTemplatePerService.clear();
	}
}
