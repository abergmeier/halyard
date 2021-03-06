/*
 * Copyright 2018 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 */

package com.netflix.spinnaker.halyard.deploy.deployment.v1;

import com.netflix.spinnaker.halyard.config.model.v1.providers.kubernetes.KubernetesAccount;
import com.netflix.spinnaker.halyard.core.DaemonResponse;
import com.netflix.spinnaker.halyard.core.RemoteAction;
import com.netflix.spinnaker.halyard.core.problem.v1.Problem;
import com.netflix.spinnaker.halyard.core.tasks.v1.DaemonTaskHandler;
import com.netflix.spinnaker.halyard.deploy.services.v1.GenerateService;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.SpinnakerRuntimeSettings;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.ServiceSettings;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.SpinnakerService;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.distributed.SidecarService;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.distributed.kubernetes.v2.KubectlServiceProvider;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.distributed.kubernetes.v2.KubernetesV2Service;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.distributed.kubernetes.v2.KubernetesV2Utils;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class KubectlDeployer implements Deployer<KubectlServiceProvider,AccountDeploymentDetails<KubernetesAccount>> {
  @Override
  public RemoteAction deploy(KubectlServiceProvider serviceProvider,
      AccountDeploymentDetails<KubernetesAccount> deploymentDetails,
      GenerateService.ResolvedConfiguration resolvedConfiguration,
      List<SpinnakerService.Type> serviceTypes,
      boolean waitForCompletion) {
    List<KubernetesV2Service> services = serviceProvider.getServicesByPriority(serviceTypes);
    services.stream().forEach((service) -> {
      if (service instanceof SidecarService) {
        return;
      }

      ServiceSettings settings = resolvedConfiguration.getServiceSettings((SpinnakerService) service);
      if (settings == null) {
        return;
      }

      if (settings.getEnabled() != null && !settings.getEnabled()) {
        return;
      }

      if (settings.getSkipLifeCycleManagement() != null && settings.getSkipLifeCycleManagement()) {
        return;
      }

      DaemonResponse.StaticRequestBuilder<Void> builder = new DaemonResponse.StaticRequestBuilder<>(
          () -> {
            DaemonTaskHandler.newStage("Deploying " + service.getServiceName() + " with kubectl");

            KubernetesAccount account = deploymentDetails.getAccount();
            String namespaceDefinition = service.getNamespaceYaml(resolvedConfiguration);
            String serviceDefinition = service.getServiceYaml(resolvedConfiguration);

            if (!KubernetesV2Utils.exists(account, namespaceDefinition)) {
              KubernetesV2Utils.apply(account, namespaceDefinition);
            }

            if (!KubernetesV2Utils.exists(account, serviceDefinition)) {
              KubernetesV2Utils.apply(account, serviceDefinition);
            }

            String resourceDefinition = service.getResourceYaml(deploymentDetails, resolvedConfiguration);
            DaemonTaskHandler.message("Running kubectl apply on the resource definition...");
            KubernetesV2Utils.apply(account, resourceDefinition);

            if (waitForCompletion) {
              DaemonTaskHandler.message("Waiting for service to be ready...");
              while (!KubernetesV2Utils.isReady(account, service.getNamespace(settings), service.getServiceName())) {
                DaemonTaskHandler.safeSleep(TimeUnit.SECONDS.toMillis(5));
              }
            }

            return null;
          });
      DaemonTaskHandler
          .submitTask(builder::build, "Deploy " + service.getServiceName(), TimeUnit.MINUTES.toMillis(4));
    });

    DaemonTaskHandler.message("Waiting on deployments to complete");
    DaemonTaskHandler.reduceChildren(null, (t1, t2) -> null, (t1, t2) -> null)
        .getProblemSet().throwifSeverityExceeds(Problem.Severity.WARNING);

    return new RemoteAction();
  }

  @Override
  public void rollback(KubectlServiceProvider serviceProvider,
      AccountDeploymentDetails<KubernetesAccount> deploymentDetails,
      SpinnakerRuntimeSettings runtimeSettings,
      List<SpinnakerService.Type> serviceTypes) {
    throw new UnsupportedOperationException("todo(lwander)");
  }

  @Override
  public void collectLogs(KubectlServiceProvider serviceProvider,
      AccountDeploymentDetails<KubernetesAccount> deploymentDetails,
      SpinnakerRuntimeSettings runtimeSettings,
      List<SpinnakerService.Type> serviceTypes) {
    throw new UnsupportedOperationException("todo(lwander)");
  }

  @Override
  public RemoteAction connectCommand(KubectlServiceProvider serviceProvider,
      AccountDeploymentDetails<KubernetesAccount> deploymentDetails,
      SpinnakerRuntimeSettings runtimeSettings,
      List<SpinnakerService.Type> serviceTypes) {
    RemoteAction result = new RemoteAction();

    String connectCommands = String.join(" &\n", serviceTypes.stream()
        .map(t -> serviceProvider.getService(t)
            .connectCommand(deploymentDetails, runtimeSettings))
        .collect(Collectors.toList()));
    result.setScript("#!/bin/bash\n" + connectCommands);
    result.setScriptDescription(
        "The generated script will open connections to the API & UI servers using ssh tunnels");
    result.setAutoRun(false);
    return result;
  }

  @Override
  public void flushInfrastructureCaches(KubectlServiceProvider serviceProvider,
      AccountDeploymentDetails<KubernetesAccount> deploymentDetails,
      SpinnakerRuntimeSettings runtimeSettings) {
    throw new UnsupportedOperationException("todo(lwander)");
  }

  @Override
  public void deleteDisabledServices(KubectlServiceProvider serviceProvider,
      AccountDeploymentDetails<KubernetesAccount> deploymentDetails,
      GenerateService.ResolvedConfiguration resolvedConfiguration,
      List<SpinnakerService.Type> serviceTypes) {

    List<KubernetesV2Service> services = serviceProvider.getServicesByPriority(serviceTypes);
    services.stream().forEach((service) -> {
      if (service instanceof SidecarService) {
        return;
      }

      ServiceSettings settings = resolvedConfiguration.getServiceSettings((SpinnakerService) service);
      if (settings == null) {
        return;
      }

      if (settings.getEnabled() == null || settings.getEnabled()) {
        return;
      }

      if (settings.getSkipLifeCycleManagement() != null && settings.getSkipLifeCycleManagement()) {
        return;
      }
      KubernetesAccount account = deploymentDetails.getAccount();

      DaemonTaskHandler.newStage("Deleting disabled service " + service.getServiceName() + " with kubectl");
      DaemonTaskHandler.message("Running kubectl delete on the resource, service, and secret definitions...");
      KubernetesV2Utils.delete(account, service.getNamespace(settings), service.getServiceName());
    });
  }
}
