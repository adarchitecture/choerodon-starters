package io.choerodon.asgard.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.choerodon.asgard.saga.dto.PollBatchDTO;
import io.choerodon.asgard.saga.dto.PollCodeDTO;
import io.choerodon.asgard.saga.dto.SagaTaskInstanceDTO;
import io.choerodon.asgard.saga.dto.SagaTaskInstanceStatusDTO;
import io.choerodon.core.saga.SagaDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.netflix.eureka.CloudEurekaInstanceConfig;
import org.springframework.cloud.netflix.eureka.EurekaInstanceConfigBean;
import org.springframework.cloud.netflix.eureka.serviceregistry.EurekaRegistration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import javax.annotation.PostConstruct;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class SagaMonitor {

    private static final Logger LOGGER = LoggerFactory.getLogger(SagaMonitor.class);

    private ChoerodonSagaProperties choerodonSagaProperties;

    private Optional<EurekaRegistration> eurekaRegistration;

    private SagaClient sagaClient;

    private Executor executor;

    static final Map<String, SagaTaskInvokeBean> invokeBeanMap = new HashMap<>();

    private DataSourceTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static volatile AtomicBoolean canExecutingFlag = new AtomicBoolean(true);

    private static final Set<Long> processingIds = Collections.synchronizedSet(new HashSet<>());


    public SagaMonitor(ChoerodonSagaProperties choerodonSagaProperties,
                       SagaClient sagaClient,
                       Executor executor,
                       DataSourceTransactionManager transactionManager,
                       Optional<EurekaRegistration> eurekaRegistration) {
        this.choerodonSagaProperties = choerodonSagaProperties;
        this.sagaClient = sagaClient;
        this.executor = executor;
        this.eurekaRegistration = eurekaRegistration;
        this.transactionManager = transactionManager;
    }

    @PostConstruct
    private void start() {
        ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(1);
        List<PollCodeDTO> codeDTOS = invokeBeanMap.entrySet().stream().map(t -> new PollCodeDTO(t.getValue().sagaTask.sagaCode(),
                t.getValue().sagaTask.code())).collect(Collectors.toList());
        if (eurekaRegistration.isPresent()) {
            CloudEurekaInstanceConfig cloudEurekaInstanceConfig = eurekaRegistration.get().getInstanceConfig();
            if (cloudEurekaInstanceConfig instanceof EurekaInstanceConfigBean) {
                EurekaInstanceConfigBean eurekaInstanceConfigBean = (EurekaInstanceConfigBean) cloudEurekaInstanceConfig;
                String instance = eurekaInstanceConfigBean.getIpAddress() + ":" + eurekaInstanceConfigBean.getNonSecurePort();
                scheduledExecutorService.scheduleWithFixedDelay(() -> {
                    if (canExecutingFlag.compareAndSet(true, false) && processingIds.isEmpty()) {
                        try {
                            List<SagaTaskInstanceDTO> list = sagaClient.pollBatch(new PollBatchDTO(instance, codeDTOS));
                            LOGGER.debug("poll sagaTaskInstances from asgard, time {} instance {} size {}", System.currentTimeMillis(), instance, list.size());
                            list.forEach(t -> {
                                processingIds.add(t.getId());
                                executor.execute(new InvokeTask(t));
                            });
                        } catch (Exception e) {
                            LOGGER.info("error.pollSagaTaskInstances {}", e.getMessage());
                        } finally {
                            canExecutingFlag.set(true);
                        }
                    }
                }, 20, choerodonSagaProperties.getPollInterval(), TimeUnit.SECONDS);
            }
        }

    }

    private class InvokeTask implements Runnable {

        private final SagaTaskInstanceDTO dto;

        InvokeTask(SagaTaskInstanceDTO dto) {
            this.dto = dto;
        }

        @Override
        public void run() {
            try {
                invoke(dto);
            } catch (Exception e) {
                LOGGER.error("message consume exception when InvokeTask, cause {}", e.getMessage());
            } finally {
                processingIds.remove(dto.getId());
            }
        }
    }

    private void invoke(SagaTaskInstanceDTO data) {
        final String key = data.getSagaCode() + data.getTaskCode();
        SagaTaskInvokeBean invokeBean = invokeBeanMap.get(key);
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setPropagationBehavior(invokeBean.sagaTask.transactionDefinition());
        TransactionStatus status = transactionManager.getTransaction(def);
        try {
            invokeBean.method.setAccessible(true);
            Object result = invokeBean.method.invoke(invokeBean.object, data.getInput());
            String resultData = null;
            if (result != null) {
                resultData = objectMapper.writeValueAsString(result);
            }
            sagaClient.updateStatus(data.getId(), new SagaTaskInstanceStatusDTO(data.getId(),
                    SagaDefinition.InstanceStatus.STATUS_COMPLETED.name(), resultData, null));
            transactionManager.commit(status);
        } catch (Exception e) {
            transactionManager.rollback(status);
            String errorMsg = getErrorInfoFromException(e);
            sagaClient.updateStatus(data.getId(), new SagaTaskInstanceStatusDTO(data.getId(),
                    SagaDefinition.InstanceStatus.STATUS_FAILED.name(), null, errorMsg));
            LOGGER.error("message consume exception, msg : {}, cause {}", data, errorMsg);
        }
    }

    private String getErrorInfoFromException(Exception e) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            return "\r\n" + sw.toString() + "\r\n";
        } catch (Exception e2) {
            return "bad getErrorInfoFromException";
        }
    }

}
