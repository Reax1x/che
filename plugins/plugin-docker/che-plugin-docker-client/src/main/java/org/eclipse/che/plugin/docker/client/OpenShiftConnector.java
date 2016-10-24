/*******************************************************************************
 * Copyright (c) 2012-2016 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.plugin.docker.client;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.openshift.internal.restclient.ResourceFactory;
import com.openshift.internal.restclient.model.DeploymentConfig;
import com.openshift.internal.restclient.model.Pod;
import com.openshift.internal.restclient.model.Port;
import com.openshift.internal.restclient.model.Service;
import com.openshift.restclient.ClientBuilder;
import com.openshift.restclient.IClient;
import com.openshift.restclient.IResourceFactory;
import com.openshift.restclient.ResourceKind;
import com.openshift.restclient.images.DockerImageURI;
import com.openshift.restclient.model.*;
import com.openshift.restclient.model.deploy.DeploymentTriggerType;
import com.openshift.restclient.model.volume.IVolumeMount;
import org.eclipse.che.plugin.docker.client.json.ContainerCreated;
import org.eclipse.che.plugin.docker.client.json.ContainerInfo;
import org.eclipse.che.plugin.docker.client.json.PortBinding;
import org.eclipse.che.plugin.docker.client.params.CreateContainerParams;
import org.jboss.dmr.ModelNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.*;

/**
 * Client for OpenShift API.
 *
 * @author ML
 */
@Singleton
public class OpenShiftConnector {
    private static final Logger LOG = LoggerFactory.getLogger(OpenShiftConnector.class);
    // Docker uses uppercase in first letter in names of json objects, e.g. {"Id":"123"} instead of {"id":"123"}
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping()
            .setFieldNamingPolicy(FieldNamingPolicy.UPPER_CAMEL_CASE)
            .create();
    private static final String VERSION = "v1";
    private static final String PROJECT_NAME = "eclipse-che";
    private static final String CHE_HOSTNAME = "che.openshift.adb";
    private static final String CHE_SERVICEACCOUNT = "cheserviceaccount";


    private final IClient client;
    private final IResourceFactory factory;

    @Inject
    public OpenShiftConnector() {
        this.client = new ClientBuilder("https://10.0.2.15:8443/")
                .withUserName("openshift-dev")
                .withPassword("devel")
                .build();
        this.factory = new ResourceFactory(client);
    }

    public ContainerCreated createContainer(CreateContainerParams createContainerParams) throws IOException {

        String resourcesPrefix = "che-workspace";
        String containerName = createContainerParams.getContainerName();
        String sanitizedContaninerName = containerName.substring(9).replace('_', '-');
        String imageName = "mariolet/che-ws-agent";//"172.30.166.244:5000/eclipse-che/che-ws-agent:latest";//createContainerParams.getContainerConfig().getImage();
        String containerid = "";

        List<IProject> list = client.list(ResourceKind.PROJECT);
        IProject cheproject = list.stream().filter(p -> p.getName().equals(PROJECT_NAME)).findFirst().orElse(null);
        if (cheproject == null) {
            LOG.equals("Project" + PROJECT_NAME + "not found");
            throw new IOException("Project" + PROJECT_NAME + "not found");
        }

        // Create che-ws service
        IService service = factory.create(VERSION, ResourceKind.SERVICE);
        ((Service) service).setNamespace(cheproject.getNamespace()); //this will be the project's namespace
        ((Service) service).setName(resourcesPrefix + "-service");
        ((Service) service).setType("NodePort");

        //Map<String, Map<String, String>> ports = createContainerParams.getContainerConfig().getExposedPorts();
        List<IServicePort> openShiftPorts = putServicePorts();
        service.setPorts(openShiftPorts);

        service.setSelector("deploymentConfig", (resourcesPrefix + "-dc"));
        LOG.debug(String.format("Stubbing service: %s", service));
        client.create(service);

        // Create che-ws deployment config
        IDeploymentConfig dc = factory.create(VERSION, ResourceKind.DEPLOYMENT_CONFIG);
        ((DeploymentConfig) dc).setName(resourcesPrefix + "-dc");
        ((DeploymentConfig) dc).setNamespace(cheproject.getName());
        ((DeploymentConfig) dc).getNode().get("spec").get("template").get("spec").get("dnsPolicy").set("Default");
        dc.setReplicas(1);
        dc.setReplicaSelector("deploymentConfig", resourcesPrefix + "-dc");
        dc.setServiceAccountName(CHE_SERVICEACCOUNT);
        Set<IPort> containerPorts = new HashSet<>();
        putContainerPorts(containerPorts);
        Map<String, String> envVariables = new HashMap<>();
        String[] env = createContainerParams.getContainerConfig().getEnv();
        String workspaceID = "";
        for (String envVar : env) {
            if (envVar.startsWith("CHE_WORKSPACE_ID"))   {
                workspaceID = envVar.substring(envVar.indexOf('=')+1);
            }
        }
        putEnvVariables(envVariables, workspaceID);
        dc.addContainer(sanitizedContaninerName,
                new DockerImageURI(imageName),
                containerPorts,
                envVariables,
                Collections.emptyList());
        dc.getContainer(sanitizedContaninerName).setImagePullPolicy("Always");
//        Set<IVolumeMount> volumeMounts = new HashSet<>();
//        putVolumesMount(volumeMounts);
//        dc.getContainer(containerName).setVolumeMounts(volumeMounts);
        dc.addTrigger(DeploymentTriggerType.CONFIG_CHANGE);
        client.create(dc);

        containerid = waitAndRetrieveContainerID(cheproject, dc);
        if (containerid == null) {
            throw new RuntimeException("Failed to get the ID of the container running in the OpenShift pod");
        }

        return new ContainerCreated(containerid, null);
    }

    private String waitAndRetrieveContainerID(IProject cheproject, IDeploymentConfig dc) {
        String deployerLabelKey = "openshift.io/deployer-pod-for.name";
        for (int i = 0; i < 120; i++) {
            try {
                Thread.sleep(1000);                 //1000 milliseconds is one second.
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }

            List<IPod> pods = client.list(ResourceKind.POD, cheproject.getNamespace(), Collections.emptyMap());
            long deployPodNum = pods.stream().filter(p -> p.getLabels().keySet().contains(deployerLabelKey)).count();

            if (deployPodNum == 0) {
                LOG.info("Pod has been deployed.");
                for (IPod pod : pods) {
                    if (pod.getLabels().get("deploymentConfig").equals(dc.getName())) {
                        ModelNode containerID = ((Pod) pod).getNode().get("status").get("containerStatuses").get(0).get("containerID");
                        return containerID.toString().substring(10, 74);
                    }
                }
            }
        }
        return null;
    }

    private void putVolumesMount(Set<IVolumeMount> volumeMounts, String workspaceName) {
        IVolumeMount volumeMount1 = new IVolumeMount() {
            public String getName() {
                return "terminal";
            }

            public String getMountPath() {
                return "/mnt/che/terminal";
            }

            public boolean isReadOnly() {
                return true;
            }

            public void setName(String name) {
            }

            public void setMountPath(String path) {
            }

            public void setReadOnly(boolean readonly) {
            }
        };
        volumeMounts.add(volumeMount1);
        IVolumeMount volumeMount2 = new IVolumeMount() {
            public String getName() {
                return "ws-agent";
            }

            public String getMountPath() {
                return "/mnt/che/ws-agent.tar.gz";
            }

            public boolean isReadOnly() {
                return true;
            }

            public void setName(String name) {
            }

            public void setMountPath(String path) {
            }

            public void setReadOnly(boolean readonly) {
            }
        };
        volumeMounts.add(volumeMount2);
        IVolumeMount volumeMount3 = new IVolumeMount() {
            public String getName() {
                return "ws-agent";
            }

            public String getMountPath() {
                return "/home/user/che/workspaces/" + workspaceName;
            }

            public boolean isReadOnly() {
                return false;
            }

            public void setName(String name) {
            }

            public void setMountPath(String path) {
            }

            public void setReadOnly(boolean readonly) {
            }
        };
        volumeMounts.add(volumeMount3);
    }

//    private List<IServicePort> putServicePorts(Map<String, Map<String, String>> ports) {
//        List<IServicePort> openShiftPorts = new ArrayList<>();
//        for (Map.Entry<String, Map<String, String>> dockerPort : ports.entrySet()) {
//            int portNum = Integer.parseInt(dockerPort.getKey().split("\\/")[0]);
//            IServicePort openShiftPort = OpenShiftPortFactory.createServicePort(
//                    "port-" + portNum,
//                    "TCP",
//                    portNum,
//                    portNum);
//            openShiftPorts.add(openShiftPort);
//        }
//        return openShiftPorts;
//    }

    private List<IServicePort> putServicePorts() {
        List<IServicePort> openShiftPorts = new ArrayList<>();

        IServicePort openShiftPort1 = OpenShiftPortFactory.createServicePort(
                "ssh",
                "tcp",
                22,
                22);
        openShiftPorts.add(openShiftPort1);

        IServicePort openShiftPort2 = OpenShiftPortFactory.createServicePort(
                "wsagent",
                "tcp",
                4401,
                4401);
        openShiftPorts.add(openShiftPort2);

        IServicePort openShiftPort3 = OpenShiftPortFactory.createServicePort(
                "wsagent-jpda",
                "tcp",
                4403,
                4403);
        openShiftPorts.add(openShiftPort3);

        IServicePort openShiftPort4 = OpenShiftPortFactory.createServicePort(
                "port1",
                "tcp",
                4411,
                4411);
        openShiftPorts.add(openShiftPort4);

        IServicePort openShiftPort5 = OpenShiftPortFactory.createServicePort(
                "tomcat",
                "tcp",
                8080,
                8080);
        openShiftPorts.add(openShiftPort5);

        IServicePort openShiftPort6 = OpenShiftPortFactory.createServicePort(
                "tomcat-jpda",
                "tcp",
                8888,
                8888);
        openShiftPorts.add(openShiftPort6);

        IServicePort openShiftPort7 = OpenShiftPortFactory.createServicePort(
                "port2",
                "tcp",
                9876,
                9876);
        openShiftPorts.add(openShiftPort7);
        return openShiftPorts;
    }

    private void putContainerPorts(Set<IPort> containerPorts) {
        Port port1 = new Port(new ModelNode());
        port1.setName("ssh");
        port1.setProtocol("TCP");
        port1.setContainerPort(22);
        containerPorts.add(port1);
        Port port2 = new Port(new ModelNode());
        port2.setName("wsagent");
        port2.setProtocol("TCP");
        port2.setContainerPort(4401);
        containerPorts.add(port2);
        Port port3 = new Port(new ModelNode());
        port3.setName("wsagent-jpda");
        port3.setProtocol("TCP");
        port3.setContainerPort(4403);
        containerPorts.add(port3);
        Port port4 = new Port(new ModelNode());
        port4.setName("port1");
        port4.setProtocol("TCP");
        port4.setContainerPort(4411);
        containerPorts.add(port4);
        Port port5 = new Port(new ModelNode());
        port5.setName("tomcat");
        port5.setProtocol("TCP");
        port5.setContainerPort(8080);
        containerPorts.add(port5);
        Port port6 = new Port(new ModelNode());
        port6.setName("tomcat-jpda");
        port6.setProtocol("TCP");
        port6.setContainerPort(8888);
        containerPorts.add(port6);
        Port port7 = new Port(new ModelNode());
        port7.setName("port2");
        port7.setProtocol("TCP");
        port7.setContainerPort(9876);
        containerPorts.add(port7);
    }

    private void putEnvVariables(Map<String, String> envVariables, String workspaceID) {
        envVariables.put("CHE_LOCAL_CONF_DIR", "/mnt/che/conf");
        envVariables.put("USER_TOKEN", "dummy_token");
        envVariables.put("CHE_API_ENDPOINT", "http://172.17.0.4:8080/wsmaster/api");
        envVariables.put("JAVA_OPTS", "-Xms256m -Xmx2048m -Djava.security.egd=file:/dev/./urandom");
        envVariables.put("CHE_WORKSPACE_ID", workspaceID);
        envVariables.put("CHE_PROJECTS_ROOT", "/projects");
//        envVariables.put("PATH","/opt/jdk1.8.0_45/bin:/home/user/apache-maven-3.3.9/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
//        envVariables.put("MAVEN_VERSION","3.3.9");
//        envVariables.put("JAVA_VERSION","8u45");
//        envVariables.put("JAVA_VERSION_PREFIX","1.8.0_45");
        envVariables.put("TOMCAT_HOME", "/home/user/tomcat8");
        //envVariables.put("JAVA_HOME","/opt/jdk1.8.0_45");
        envVariables.put("M2_HOME", "/home/user/apache-maven-3.3.9");
        envVariables.put("TERM", "xterm");
        envVariables.put("LANG", "en_US.UTF-8");
    }

    public ContainerInfo inspectContainer(DockerConnector docker, String container) throws IOException {
        // Proxy to Docker
        ContainerInfo info = docker.inspectContainer(container);
        if (info == null) {
            return null;
        }

        // Ignore portMapping for now
        //info.getNetworkSettings().setPortMapping();

        // Find the project
        if (info.getNetworkSettings() != null) {
            List<IProject> list = client.list(ResourceKind.PROJECT);
            IProject cheproject = list.stream().filter(p -> p.getName().equals(PROJECT_NAME)).findFirst().orElse(null);
            if (cheproject == null) {
                LOG.equals("Project" + PROJECT_NAME + "not found");
                throw new IOException("Project" + PROJECT_NAME + "not found");
            }

            // Find the service
            List<IService> services = client.list(ResourceKind.SERVICE, cheproject.getNamespace(), Collections.emptyMap());
            IService service = services.stream().filter(s -> s.getName().equals("che-workspace-service")).findFirst().orElse(null);
            if (service == null) {
                LOG.equals("Service che-workspace-service not found");
                throw new IOException("Service che-workspace-service not found");
            }

            // Find the ports
            Map<String, List<PortBinding>> networkSettingsPorts = new HashMap<>();
            List<ModelNode> servicePorts = ((Service) service).getNode().get("spec").get("ports").asList();
            LOG.info("Adding " + servicePorts.size() + " ports");
            for (ModelNode servicePort : servicePorts) {
                String protocol = servicePort.get("protocol").asString();
                String targetPort = servicePort.get("targetPort").asString();
                String nodePort = servicePort.get("nodePort").asString();

                LOG.info("Adding port: " + targetPort + "/" + protocol);

                networkSettingsPorts.put(targetPort + "/" + protocol.toLowerCase(),
                        Collections.singletonList(new PortBinding().withHostIp("172.17.0.1").withHostPort(nodePort)));
            }
            info.getNetworkSettings().setPorts(networkSettingsPorts);
        }

        // Add labels
        if (info.getConfig() != null) {
            Map<String,String> configLabels = new HashMap<>();
            configLabels.put("che:server:8000:protocol", "http");
            configLabels.put("che:server:8000:ref", "tomcat8-debug");
            configLabels.put("che:server:8080:protocol", "http");
            configLabels.put("che:server:8080:ref", "tomcat8");
            configLabels.put("che:server:9876:protocol", "http");
            configLabels.put("che:server:9876:ref", "codeserver");
            info.getConfig().setLabels(configLabels);
        }
        return info;
    }
}