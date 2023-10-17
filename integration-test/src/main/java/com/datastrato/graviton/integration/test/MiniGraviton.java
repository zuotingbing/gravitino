/*
 * Copyright 2023 Datastrato.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.graviton.integration.test;

import static com.datastrato.graviton.Configs.ENTRY_KV_ROCKSDB_BACKEND_PATH;

import com.datastrato.graviton.Config;
import com.datastrato.graviton.Configs;
import com.datastrato.graviton.aux.AuxiliaryServiceManager;
import com.datastrato.graviton.catalog.lakehouse.iceberg.IcebergRESTService;
import com.datastrato.graviton.client.ErrorHandlers;
import com.datastrato.graviton.client.HTTPClient;
import com.datastrato.graviton.client.RESTClient;
import com.datastrato.graviton.dto.responses.VersionResponse;
import com.datastrato.graviton.exceptions.RESTException;
import com.datastrato.graviton.integration.test.util.ITUtils;
import com.datastrato.graviton.rest.RESTUtils;
import com.datastrato.graviton.server.GravitonServer;
import com.datastrato.graviton.server.ServerConfig;
import com.datastrato.graviton.server.web.JettyServerConfig;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MiniGraviton {
  private static final Logger LOG = LoggerFactory.getLogger(MiniGraviton.class);
  private MiniGravitonContext context;
  private RESTClient restClient;
  private final File mockConfDir;
  private final ServerConfig serverConfig = new ServerConfig();
  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  private String host;

  private int port;

  public MiniGraviton(MiniGravitonContext context) throws IOException {
    this.context = context;
    this.mockConfDir = Files.createTempDirectory("MiniGraviton").toFile();
    mockConfDir.mkdirs();
  }

  public void start() throws Exception {
    LOG.info("Staring MiniGraviton up...");

    String gravitonRootDir = System.getenv("GRAVITON_ROOT_DIR");

    // Generate random Graviton Server port and backend storage path, avoiding conflicts
    customizeConfigFile(
        ITUtils.joinDirPath(gravitonRootDir, "conf", "graviton.conf.template"),
        ITUtils.joinDirPath(mockConfDir.getAbsolutePath(), GravitonServer.CONF_FILE));

    Files.copy(
        Paths.get(ITUtils.joinDirPath(gravitonRootDir, "conf", "graviton-env.sh.template")),
        Paths.get(ITUtils.joinDirPath(mockConfDir.getAbsolutePath(), "graviton-env.sh")));

    Properties properties =
        serverConfig.loadPropertiesFromFile(
            new File(ITUtils.joinDirPath(mockConfDir.getAbsolutePath(), "graviton.conf")));
    serverConfig.loadFromProperties(properties);

    // Prepare delete the rocksdb backend storage directory
    try {
      FileUtils.deleteDirectory(FileUtils.getFile(serverConfig.get(ENTRY_KV_ROCKSDB_BACKEND_PATH)));
    } catch (Exception e) {
      // Ignore
    }

    // Initialize the REST client
    JettyServerConfig jettyServerConfig =
        JettyServerConfig.fromConfig(serverConfig, GravitonServer.WEBSERVER_CONF_PREFIX);
    this.host = jettyServerConfig.getHost();
    this.port = jettyServerConfig.getHttpPort();
    String URI = String.format("http://%s:%d", host, port);
    restClient = HTTPClient.builder(ImmutableMap.of()).uri(URI).build();

    Future<?> future =
        executor.submit(
            () -> {
              try {
                GravitonServer.main(
                    new String[] {
                      ITUtils.joinDirPath(mockConfDir.getAbsolutePath(), "graviton.conf")
                    });
              } catch (Exception e) {
                LOG.error("Exception in startup MiniGraviton Server ", e);
                throw new RuntimeException(e);
              }
            });
    long beginTime = System.currentTimeMillis();
    boolean started = false;
    while (System.currentTimeMillis() - beginTime < 1000 * 60 * 3) {
      Thread.sleep(500);
      started = checkIfServerIsRunning();
      if (started || future.isDone()) {
        break;
      }
    }
    if (!started) {
      throw new RuntimeException("Can not start Graviton server");
    }

    LOG.info("MiniGraviton stared.");
  }

  public void stop() throws IOException, InterruptedException {
    LOG.debug("MiniGraviton shutDown...");

    executor.shutdown();
    Thread.sleep(500);
    executor.shutdownNow();

    long beginTime = System.currentTimeMillis();
    boolean started = true;
    while (System.currentTimeMillis() - beginTime < 1000 * 60 * 3) {
      Thread.sleep(500);
      started = checkIfServerIsRunning();
      if (!started) {
        break;
      }
    }

    restClient.close();
    try {
      FileUtils.deleteDirectory(mockConfDir);
      FileUtils.deleteDirectory(FileUtils.getFile(serverConfig.get(ENTRY_KV_ROCKSDB_BACKEND_PATH)));
    } catch (Exception e) {
      // Ignore
    }

    if (started) {
      throw new RuntimeException("Can not stop Graviton server");
    }

    LOG.debug("MiniGraviton terminated.");
  }

  public Config getServerConfig() {
    return serverConfig;
  }

  Map<String, String> getIcebergRestServiceConfigs() throws IOException {
    Map<String, String> customConfigs = new HashMap<>();

    String icebergJarPath =
        Paths.get("catalogs", "catalog-lakehouse-iceberg", "build", "libs").toString();
    String icebergConfigPath =
        Paths.get("catalogs", "catalog-lakehouse-iceberg", "src", "main", "resources").toString();
    customConfigs.put(
        AuxiliaryServiceManager.GRAVITON_AUX_SERVICE_PREFIX
            + IcebergRESTService.SERVICE_NAME
            + "."
            + AuxiliaryServiceManager.AUX_SERVICE_CLASSPATH,
        String.join(",", icebergJarPath, icebergConfigPath));

    customConfigs.put(
        AuxiliaryServiceManager.GRAVITON_AUX_SERVICE_PREFIX
            + IcebergRESTService.SERVICE_NAME
            + "."
            + JettyServerConfig.WEBSERVER_HTTP_PORT.getKey(),
        String.valueOf(RESTUtils.findAvailablePort(3000, 4000)));
    return customConfigs;
  }

  // Customize the config file
  private void customizeConfigFile(String configTempFileName, String configFileName)
      throws IOException {
    Map<String, String> configMap = new HashMap<>();
    configMap.put(
        GravitonServer.WEBSERVER_CONF_PREFIX + JettyServerConfig.WEBSERVER_HTTP_PORT.getKey(),
        String.valueOf(RESTUtils.findAvailablePort(2000, 3000)));
    configMap.put(
        Configs.ENTRY_KV_ROCKSDB_BACKEND_PATH.getKey(), "/tmp/graviton-" + UUID.randomUUID());

    configMap.putAll(getIcebergRestServiceConfigs());
    configMap.putAll(context.customConfig);

    ITUtils.rewriteConfigFile(configTempFileName, configFileName, configMap);
  }

  private boolean checkIfServerIsRunning() {
    String URI = String.format("http://%s:%d", host, port);
    LOG.info("checkIfServerIsRunning() URI: {}", URI);

    restClient = HTTPClient.builder(ImmutableMap.of()).uri(URI).build();
    VersionResponse response = null;
    try {
      response =
          restClient.get(
              "api/version",
              VersionResponse.class,
              Collections.emptyMap(),
              ErrorHandlers.restErrorHandler());
    } catch (RESTException e) {
      LOG.warn("checkIfServerIsRunning() fails, GravitonServer is not running");
    }
    if (response != null && response.getCode() == 0) {
      return true;
    } else {
      LOG.warn("checkIfServerIsRunning() fails, GravitonServer is not running");
    }

    return false;
  }
}
