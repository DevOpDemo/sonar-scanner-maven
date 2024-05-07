/*
 * SonarQube Scanner for Maven
 * Copyright (C) 2009-2024 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonarsource.scanner.maven.bootstrap;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.sonarsource.scanner.lib.AnalysisProperties;
import org.sonarsource.scanner.lib.ScannerEngineBootstrapper;
import org.sonarsource.scanner.lib.ScannerEngineFacade;

/**
 * Configure properties and bootstrap using SonarQube scanner API
 */
public class ScannerBootstrapper {

  static final String UNSUPPORTED_BELOW_SONARQUBE_56_MESSAGE = "With SonarQube server prior to 5.6, use sonar-maven-plugin <= 3.3";

  private final Log log;
  private final MavenSession session;
  private final ScannerEngineBootstrapper bootstrapper;
  private final MavenProjectConverter mavenProjectConverter;
  private String serverVersion;
  private final PropertyDecryptor propertyDecryptor;

  public ScannerBootstrapper(Log log, MavenSession session, ScannerEngineBootstrapper bootstrapper, MavenProjectConverter mavenProjectConverter,
    PropertyDecryptor propertyDecryptor) {
    this.log = log;
    this.session = session;
    this.bootstrapper = bootstrapper;
    this.mavenProjectConverter = mavenProjectConverter;
    this.propertyDecryptor = propertyDecryptor;
  }

  public void execute() throws MojoExecutionException {
    logEnvironmentInformation();
    try (ScannerEngineFacade engineFacade = bootstrapper.bootstrap()) {

      if (engineFacade.isSonarCloud()) {
        log.info("Communicating with SonarCloud");
      } else {
        serverVersion = engineFacade.getServerVersion();
        log.info("Communicating with SonarQube Server " + serverVersion);
        checkSQVersion();
      }

      engineFacade.analyze(collectProperties());
    } catch (Exception e) {
      throw new MojoExecutionException(e.getMessage(), e);
    }
  }

  @VisibleForTesting
  Map<String, String> collectProperties()
    throws MojoExecutionException {
    List<MavenProject> sortedProjects = session.getProjects();
    MavenProject topLevelProject = null;
    for (MavenProject project : sortedProjects) {
      if (project.isExecutionRoot()) {
        topLevelProject = project;
        break;
      }
    }
    if (topLevelProject == null) {
      throw new IllegalStateException("Maven session does not declare a top level project");
    }
    Properties userProperties = session.getUserProperties();
    Map<String, String> props = mavenProjectConverter.configure(sortedProjects, topLevelProject, userProperties);
    props.putAll(propertyDecryptor.decryptProperties(props));
    if (shouldCollectAllSources(userProperties)) {
      log.info("Parameter " + MavenScannerProperties.PROJECT_SCAN_ALL_SOURCES + " is enabled. The scanner will attempt to collect additional sources.");
      if (!mavenProjectConverter.isSourceDirsOverridden()) {
        collectAllSources(props);
      } else {
        String warning = "Parameter " + MavenScannerProperties.PROJECT_SCAN_ALL_SOURCES + " is enabled but " +
          "the scanner will not collect additional sources because sonar.sources has been overridden.";
        log.warn(warning);
      }
    }

    return props;
  }

  private static boolean shouldCollectAllSources(Properties userProperties) {
    return Boolean.TRUE.equals(Boolean.parseBoolean(userProperties.getProperty(MavenScannerProperties.PROJECT_SCAN_ALL_SOURCES)));
  }

  private void collectAllSources(Map<String, String> props) {
    String projectBasedir = props.get(AnalysisProperties.PROJECT_BASEDIR);
    // Exclude the files and folders covered by sonar.sources and sonar.tests (and sonar.exclusions) as computed by the MavenConverter
    // Combine all the sonar.sources at the top-level and by module
    List<String> coveredSources = props.entrySet().stream()
      .filter(k -> k.getKey().endsWith(AnalysisProperties.PROJECT_SOURCE_DIRS) || k.getKey().endsWith(AnalysisProperties.PROJECT_TEST_DIRS))
      .map(Map.Entry::getValue)
      .filter(value -> !value.isEmpty())
      .flatMap(value -> MavenUtils.splitAsCsv(value).stream())
      .collect(Collectors.toList());
    // Crawl the FS for files we want
    List<String> collectedSources;
    try {
      Set<Path> existingSources = coveredSources.stream()
        .map(Paths::get)
        .collect(Collectors.toSet());
      SourceCollector visitor = new SourceCollector(existingSources, mavenProjectConverter.getSkippedBasedDirs());
      Files.walkFileTree(Paths.get(projectBasedir), visitor);
      collectedSources = visitor.getCollectedSources().stream()
        .map(file -> file.toAbsolutePath().toString())
        .collect(Collectors.toList());
      List<String> mergedSources = new ArrayList<>();
      mergedSources.addAll(MavenUtils.splitAsCsv(props.get(AnalysisProperties.PROJECT_SOURCE_DIRS)));
      mergedSources.addAll(collectedSources);
      props.put(AnalysisProperties.PROJECT_SOURCE_DIRS, MavenUtils.joinAsCsv(mergedSources));
    } catch (IOException e) {
      log.warn(e);
    }
  }

  private void checkSQVersion() {
    if (isVersionPriorTo("5.6")) {
      throw new UnsupportedOperationException(UNSUPPORTED_BELOW_SONARQUBE_56_MESSAGE);
    }
  }

  boolean isVersionPriorTo(String version) {
    if (serverVersion == null) {
      return true;
    }
    return new ComparableVersion(serverVersion).compareTo(new ComparableVersion(version)) < 0;
  }

  private void logEnvironmentInformation() {
    String vmInformation = String.format(
      "Java %s %s (%s-bit)",
      SystemWrapper.getProperty("java.version"),
      SystemWrapper.getProperty("java.vm.vendor"),
      SystemWrapper.getProperty("sun.arch.data.model")
    );
    log.info(vmInformation);
    String operatingSystem = String.format(
      "%s %s (%s)",
      SystemWrapper.getProperty("os.name"),
      SystemWrapper.getProperty("os.version"),
      SystemWrapper.getProperty("os.arch")
    );
    log.info(operatingSystem);
    String mavenOptions = SystemWrapper.getenv("MAVEN_OPTS");
    if (mavenOptions != null) {
      log.info(String.format("MAVEN_OPTS=%s", mavenOptions));
    }
  }

}
