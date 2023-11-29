package com.kedacom.ctsp.iomp.k8s;

import cn.hutool.core.util.RandomUtil;
import com.alibaba.fastjson.JSON;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.kedacom.ctsp.iomp.k8s.Entity.*;
import com.kedacom.ctsp.iomp.k8s.common.util.IPUtils;
import com.kedacom.ctsp.iomp.k8s.common.util.MySQLUtil;
import com.kedacom.ctsp.iomp.k8s.common.util.SqliteUtil;
import com.kedacom.ctsp.iomp.k8s.common.util.YamlUtil;
import com.kedacom.ctsp.iomp.k8s.constant.K8sKindConstant;
import com.kedacom.ctsp.iomp.k8s.operator.K8sConfigmapOperator;
import com.kedacom.ctsp.lang.exception.CommonException;
import com.kedacom.ctsp.lang.mapper.BeanMapper;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

import static com.kedacom.ctsp.iomp.k8s.constant.K8sConstants.CLUSTER_VERSION_CONFIGMAP;

/**
 * @author sunhaoyue
 * @date Created in 2023/11/7 18:50
 */

@Slf4j
public class Application {
    String DEFAULT = "default";
    static String CLUSTER_VERSION_KEY = "cluster-version";
    String CLUSTER_VERSION_DEFAULT = "v2.5.1";

    public static Integer defaultSecurePort = 6443;

    static String HARDCORE_GATEWAY_IP = "192.177.1.100";
    /**
     * 极限模式
     */
    static String EXTREME = "extreme";
    /**
     * 极限模式
     */
    static String HARDCORE = "hardcore";

    public static final String APM_JAVA_OPTS_PARAM = "-javaagent:/apm-agent/skywalking-agent.jar";


    //Main-Class: com.kedacom.ctsp.iomp.k8s.Application
    public static void main(String[] args) {
        // 生成 clientHolder
        if (args.length > 1) {
            String gatewayIp = args[0];
            String kubernetesPkiPath = args[1];
            K8sClientHolder k8sClientHolder = createK8sClientHolderByPath(gatewayIp, kubernetesPkiPath);
            clearApmAgent(k8sClientHolder);
        } else {
            System.out.println("参数不满足条件,请以此输入集群的某个master节点、集群证书存储路径.空格隔开");
        }

    }

    public static String generateBase64Image() {
        if (2 <= 1) {
            System.out.println("count");
        }
        if (1>=2) {
            System.out.println("count");
        }
        // 生成4位随机码
        String randomCode = RandomUtil.randomString(4);
        int width = 100;
        int height = 40;
        // base64图片前缀
        String pre = "data:image/jpg;base64,";
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        Graphics graphics = image.getGraphics();
        // 设置背景色
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);

        // 设置边框颜色
        graphics.setColor(Color.BLACK);
        graphics.drawRect(0, 0, width - 1, height - 1);

        // 设置字体颜色和样式
        graphics.setColor(Color.BLACK);
        Font font = new Font("Arial", Font.BOLD, 20);
        graphics.setFont(font);

        // 在图片上绘制随机码
        graphics.drawString(randomCode, 30, 25);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", baos);
            baos.flush();
            byte[] imageBytes = baos.toByteArray();
            baos.close();
            return pre + Base64.getEncoder().encodeToString(imageBytes);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    public static void clearApmAgent(K8sClientHolder clientHolder) {
        // 遍历所有的 Deployments 服务；获取服务的yaml。服务的initContainers  存在 apm-agent 清理删除:

        List<String> serviceName = Lists.newArrayList();
        DeploymentList deploymentList = clientHolder.getClient().apps().deployments().inAnyNamespace().list();

        List<Deployment> deployments = deploymentList.getItems();
        for (Deployment deployment : deployments) {
            //if (StringUtils.equals("gg-clog-es", deployment.getMetadata().getName())) {
            List<Container> initContainers = deployment.getSpec().getTemplate().getSpec().getInitContainers();
            Iterator<Container> iterator = initContainers.iterator();
            while (iterator.hasNext()) {
                Container initContainer = iterator.next();
                if ("apm-agent".equals(initContainer.getName())) {
                    iterator.remove();
                    //更新主容器的环境变量 拿掉
                    Container mainContainer = getMainContainer(deployment);
                    for (EnvVar envVar : mainContainer.getEnv()) {
                        if (!StringUtils.equals(envVar.getName(), "JAVA_OPTS")) {
                            continue;
                        }
                        String javaOpts = StringUtils.remove(envVar.getValue(), "-javaagent:/apm-agent/skywalking-agent.jar");
                        envVar.setValue(javaOpts.trim());
                        break;
                    }
                    List<Container> containers = setMainContainer(deployment.getSpec().getTemplate().getSpec().getContainers(), mainContainer);
                    //去除后重建
                    deployment.getSpec().getTemplate().getSpec().setContainers(containers);
                    deployment.getSpec().getTemplate().getSpec().setInitContainers(initContainers);
                    serviceName.add(deployment.getMetadata().getName());
                    clientHolder.getClient().apps().deployments().inNamespace(deployment.getMetadata().getNamespace()).createOrReplace(deployment);
                }
            }

            //}
        }
        System.out.println(String.format("清理的apm-agent的服务包含 : [%s] ", Joiner.on(",").join(serviceName)));
    }

    private static List<Container> setMainContainer(List<Container> originalContainers, Container targetContainer) {
        List<Container> result = Lists.newArrayList();
        for (Container container : originalContainers) {
            if (StringUtils.equals(container.getName(), targetContainer.getName())) {
                result.add(targetContainer);
                continue;
            }
            result.add(container);
        }
        return result;
    }

    /**
     * 获取主容器
     *
     * @param from
     * @return
     */
    private static Container getMainContainer(Deployment from) {
        List<Container> containers = from.getSpec().getTemplate().getSpec().getContainers();
        if (CollectionUtils.isEmpty(containers)) {
            throw new CommonException("iomp.k8s.container.notfound", from.getMetadata().getName());
        }

        for (Container container : containers) {
            if (StringUtils.indexOf(container.getName(), from.getMetadata().getName()) >= 0) {
                return container;
            }
        }
        // 2.5.0 container name 兼容标识
        for (Container container : containers) {
            if (StringUtils.indexOf(from.getMetadata().getName(), container.getName()) >= 0) {
                return container;
            }
        }
        throw new CommonException("iomp.k8s.container.notfound", from.getMetadata().getName());
    }


    private static K8sClientHolder createK8sClientHolderByPath(String gatewayIp, String kubernetesPkiPath) {
        K8sConfig config = new K8sConfig();
        //config.setK8sName(StringUtils.isBlank(entity.getK8sSign()) ? entity.getK8sName() : entity.getK8sSign());
        config.setProtocol("https");
        config.setMasters(Lists.newArrayList(gatewayIp + ":" + (defaultSecurePort)));
        //   /data/iomp_data/kube/f9386e54027d48e7ad6bcb8fc3e5c1a0/etc/kubernetes/pki/ca.crt
        config.setCaCertFile(kubernetesPkiPath + "/ca.crt");
        config.setClientCertFile(kubernetesPkiPath + "/apiserver-kubelet-client.crt");
        config.setClientKeyFile(kubernetesPkiPath + "/apiserver-kubelet-client.key");
        log.info("create k8sHolder, masterIp={}, config={}", gatewayIp, JSON.toJSONString(config));
        return new K8sClientHolder(config);
    }

    private K8sClientHolder createK8sClientHolder(String clusterId, ClusterEntity entity, List<NodeEntity> nodeEntities) {
        List<String> gatewayIps = this.queryMasters(nodeEntities);
        List<String> masters = gatewayIps.stream().map(master -> IPUtils.getK8sMaster(master) + ":" + defaultSecurePort)
                .collect(Collectors.toList());
        K8sConfig config = new K8sConfig();
        config.setK8sName(StringUtils.isBlank(entity.getK8sSign()) ? entity.getK8sName() : entity.getK8sSign());
        config.setProtocol("https");
        if ((isExtremeMode(entity.getClusterType(), entity.getScriptType()))
                && nodeEntities.size() == 1) {
            config.setMasters(Lists.newArrayList(HARDCORE_GATEWAY_IP + ":" + (defaultSecurePort)));
        } else {
            config.setMasters(masters);
        }
        //   /data/iomp_data/kube/f9386e54027d48e7ad6bcb8fc3e5c1a0/etc/kubernetes/pki/ca.crt
        config.setCaCertFile(entity.getCaCertFile());
        config.setClientCertFile(entity.getClientCertFile());
        config.setClientKeyFile(entity.getClientKeyFile());
        log.info("create k8sHolder, clusterId={}, config={}", clusterId, JSON.toJSONString(config));
        return new K8sClientHolder(config);
    }

    /**
     * 是否为极限模式
     *
     * @return
     */
    public static boolean isExtremeMode(String clusterType, String scriptType) {
        return StringUtils.equalsIgnoreCase(clusterType, EXTREME)
                || StringUtils.equalsIgnoreCase(scriptType, HARDCORE);
    }

    public List<String> queryMasters(List<NodeEntity> entities) {
        if (CollectionUtils.isEmpty(entities)) {
            return Lists.newArrayList();
//            throw new ClusterNotCompleteException("集群中主机信息为空");
        }
        List<String> masters = new ArrayList<>();
        entities.forEach(et -> {
            if (Objects.equals(et.getIsMeta(), 1)) {
                masters.add(et.getHostIp());
            }
        });
        return masters;
    }


    public Map<String, String> getClusterInfo(K8sClientHolder clientHolder) {
        K8sConfigmapOperator configmapOperator = new K8sConfigmapOperator(clientHolder);
        ConfigMap configmap = configmapOperator.get(CLUSTER_VERSION_CONFIGMAP, DEFAULT);
        if (configmap != null && !StringUtils.equalsIgnoreCase(configmap.getKind(), K8sKindConstant.CM)) {
            configmapOperator.deleteConfigMap(CLUSTER_VERSION_CONFIGMAP, DEFAULT);
            configmap = null;
        }
        Map<String, String> result = Maps.newHashMap();
        //集群上没有值，都走默认值
        if (configmap == null || configmap.getData() == null) {
            result.put(CLUSTER_VERSION_KEY, CLUSTER_VERSION_DEFAULT);
            return result;
        }
        Map<String, String> data = configmap.getData();
        result.put(CLUSTER_VERSION_KEY, data.get(CLUSTER_VERSION_KEY));

        return result;
    }


}
