package io.quarkiverse.quinoa.deployment.packagemanager;

import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;

import org.jboss.logging.Logger;

import com.github.eirslett.maven.plugins.frontend.lib.FrontendPluginFactory;
import com.github.eirslett.maven.plugins.frontend.lib.InstallationException;
import com.github.eirslett.maven.plugins.frontend.lib.ProxyConfig;

import io.quarkiverse.quinoa.deployment.QuinoaProcessor.ProjectDirs;
import io.quarkus.runtime.configuration.ConfigurationException;

public final class PackageManagerInstall {

    private static final Logger LOG = Logger.getLogger(PackageManagerInstall.class);
    private static final String INSTALL_SUB_PATH = "node";
    public static final String NODE_BINARY = PackageManager.isWindows() ? "node.exe" : "node";
    public static final String NPM_PATH = INSTALL_SUB_PATH + "/node_modules/npm/bin/npm-cli.js";

    private PackageManagerInstall() {

    }

    public static Installation install(PackageManagerInstallConfig config, final ProjectDirs projectDirs) {

        Path installDir = resolveInstallDir(config, projectDirs).normalize();
        FrontendPluginFactory factory = new FrontendPluginFactory(null, installDir.toFile());
        if (!config.nodeVersion.isPresent()) {
            throw new ConfigurationException("node-version is required to install package manager",
                    Set.of("quarkus.quinoa.package-manager-install.node-version"));
        }
        if (Integer.parseInt(config.nodeVersion.get().split("[.]")[0]) < 4) {
            throw new ConfigurationException("Quinoa is not compatible with Node prior to v4.0.0",
                    Set.of("quarkus.quinoa.package-manager-install.node-version"));
        }
        try {
            final ProxyConfig proxy = new ProxyConfig(Collections.emptyList());
            try {
                factory.getNodeInstaller(proxy)
                        .setNodeVersion("v" + config.nodeVersion.get())
                        .setNodeDownloadRoot(config.nodeDownloadRoot)
                        .setNpmVersion(config.npmVersion)
                        .install();
            } catch (InstallationException e) {
                if (e.getCause() instanceof DirectoryNotEmptyException && e.getCause().getMessage().contains("tmp")) {
                    LOG.warnf("Quinoa was not able to delete the Node install temporary directory: %s",
                            e.getCause().getMessage());
                } else {
                    throw e;
                }
            }

            factory.getNPMInstaller(proxy)
                    .setNodeVersion("v" + config.nodeVersion.get())
                    .setNpmVersion(config.npmVersion)
                    .setNpmDownloadRoot(config.npmDownloadRoot)
                    .install();
            return resolveInstalledNpmBinary(installDir);
        } catch (InstallationException e) {
            throw new RuntimeException("Error while installing NodeJS", e);
        }
    }

    private static Path resolveInstallDir(PackageManagerInstallConfig config, ProjectDirs projectDirs) {
        final Path installPath = Path.of(config.installDir.trim());
        if (installPath.isAbsolute()) {
            return installPath;
        }
        if (projectDirs.getProjectRootDir() == null) {
            throw new ConfigurationException(
                    "Use an absolute package-manager-install.install-dir when the project root directory is not standard",
                    Set.of("quarkus.quinoa.package-manager-install.install-dir"));
        }
        return projectDirs.getProjectRootDir().resolve(installPath);
    }

    private static Installation resolveInstalledNpmBinary(Path installDirectory) {
        final Path nodeDirPath = installDirectory.resolve(INSTALL_SUB_PATH)
                .toAbsolutePath();
        final Path npmPath = installDirectory.resolve(NPM_PATH).toAbsolutePath();
        final String platformNodeDirPath = normalizePath(nodeDirPath.toString());
        final String platformNPMPath = normalizePath(npmPath.toString());
        final String packageManagerBinary = NODE_BINARY + " " + quotePathWithSpaces(platformNPMPath);
        return new Installation(platformNodeDirPath, packageManagerBinary);
    }

    public static String normalizePath(String path) {
        return PackageManager.isWindows() ? path.replaceAll("/", "\\\\") : path;
    }

    public static String quotePathWithSpaces(String path) {
        return path.contains(" ") ? "\"".concat(path).concat("\"") : path;
    }

    public static class Installation {
        private final String nodeDirPath;
        private final String packageManagerBinary;

        public Installation(String nodeDirPath, String packageManagerBinary) {
            this.nodeDirPath = nodeDirPath;
            this.packageManagerBinary = packageManagerBinary;
        }

        public String getNodeDirPath() {
            return nodeDirPath;
        }

        public String getPackageManagerBinary() {
            return packageManagerBinary;
        }
    }

}
