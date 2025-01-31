/*
 * Copyright (c) 2019, 2021, Gluon
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL GLUON BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.gluonhq.substrate.util.ios;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListParser;
import com.gluonhq.substrate.Constants;
import com.gluonhq.substrate.model.ClassPath;
import com.gluonhq.substrate.model.InternalProjectConfiguration;
import com.gluonhq.substrate.model.ProcessPaths;
import com.gluonhq.substrate.model.ReleaseConfiguration;
import com.gluonhq.substrate.util.FileOps;
import com.gluonhq.substrate.util.Logger;
import com.gluonhq.substrate.util.ProcessRunner;
import com.gluonhq.substrate.util.XcodeUtils;
import com.gluonhq.substrate.util.plist.NSDictionaryEx;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.gluonhq.substrate.Constants.META_INF_SUBSTRATE_IOS;
import static com.gluonhq.substrate.Constants.PARTIAL_PLIST_FILE;
import static com.gluonhq.substrate.model.ReleaseConfiguration.DEFAULT_BUNDLE_SHORT_VERSION;
import static com.gluonhq.substrate.model.ReleaseConfiguration.DEFAULT_BUNDLE_VERSION;

public class InfoPlist {

    private static final List<String> assets = new ArrayList<>(Arrays.asList(
            "Default-375w-667h@2x~iphone.png", "Default-414w-736h@3x~iphone.png", "Default-portrait@2x~ipad.png",
            "Default-375w-812h-landscape@3x~iphone.png", "Default-568h@2x~iphone.png", "Default-portrait~ipad.png",
            "Default-375w-812h@3x~iphone.png", "Default-landscape@2x~ipad.png", "Default@2x~iphone.png",
            "Default-414w-736h-landscape@3x~iphone.png", "Default-414w-896h@3x~iphone.png", "Default-414w-896h-landscape@3x~iphone.png",
            "Default-landscape~ipad.png", "iTunesArtwork",
            "iTunesArtwork@2x"
    ));

    private static final List<String> iconAssets = new ArrayList<>(Arrays.asList(
            "Contents.json", "Gluon-app-store-icon-1024@1x.png", "Gluon-ipad-app-icon-76@1x.png", "Gluon-ipad-app-icon-76@2x.png",
            "Gluon-ipad-notifications-icon-20@1x.png", "Gluon-ipad-notifications-icon-20@2x.png",
            "Gluon-ipad-pro-app-icon-83.5@2x.png", "Gluon-ipad-settings-icon-29@1x.png", "Gluon-ipad-settings-icon-29@2x.png",
            "Gluon-ipad-spotlight-icon-40@1x.png","Gluon-ipad-spotlight-icon-40@2x.png","Gluon-iphone-app-icon-60@2x.png",
            "Gluon-iphone-app-icon-60@3x.png","Gluon-iphone-notification-icon-20@2x.png", "Gluon-iphone-notification-icon-20@3x.png",
            "Gluon-iphone-spotlight-icon-40@2x.png", "Gluon-iphone-spotlight-icon-40@3x.png", "Gluon-iphone-spotlight-settings-icon-29@2x.png",
            "Gluon-iphone-spotlight-settings-icon-29@3x.png"
    ));

    private final XcodeUtils.SDKS sdk;
    private final InternalProjectConfiguration projectConfiguration;
    private final ProcessPaths paths;
    private final String sourceOS;
    private final XcodeUtils xcodeUtil;

    private final Path appPath;
    private final Path rootPath;
    private final Path tmpPath;
    private final Path partialPListDir;

    private Path tmpStoryboardsDir;
    private String bundleId;
    private String minOSVersion = "11.0";

    public InfoPlist(ProcessPaths paths, InternalProjectConfiguration projectConfiguration, XcodeUtils.SDKS sdk) throws IOException {
        this.paths = Objects.requireNonNull(paths);
        this.projectConfiguration = Objects.requireNonNull(projectConfiguration);
        this.sourceOS = projectConfiguration.getTargetTriplet().getOs();
        this.sdk = sdk;
        this.xcodeUtil = new XcodeUtils(sdk);
        appPath = paths.getAppPath().resolve(projectConfiguration.getAppName() + ".app");
        rootPath = paths.getSourcePath().resolve(sourceOS);
        tmpPath = paths.getTmpPath();
        partialPListDir = tmpPath.resolve("partial-plists");
    }

    public Path processInfoPlist() throws IOException, InterruptedException {
        String appName = projectConfiguration.getAppName();
        String executableName = getExecutableName(appName, sourceOS);
        String bundleIdName = getBundleId(getPlistPath(paths, sourceOS), projectConfiguration.getAppId());
        ReleaseConfiguration releaseConfiguration = projectConfiguration.getReleaseConfiguration();
        String bundleName = Objects.requireNonNullElse(releaseConfiguration.getBundleName(), appName);
        String bundleVersion = Objects.requireNonNullElse(releaseConfiguration.getBundleVersion(), DEFAULT_BUNDLE_VERSION);
        String bundleShortVersion = Objects.requireNonNullElse(releaseConfiguration.getBundleShortVersion(), DEFAULT_BUNDLE_SHORT_VERSION);

        Path userPlist = rootPath.resolve(Constants.PLIST_FILE);
        boolean inited = true;
        if (!Files.exists(userPlist)) {
            // copy plist to gensrc/ios
            Path genPlist = paths.getGenPath().resolve(sourceOS).resolve(Constants.PLIST_FILE);
            Logger.logDebug("Copy " + Constants.PLIST_FILE + " to " + genPlist.toString());
            FileOps.copyResource("/native/ios/Default-Info.plist", genPlist);
            inited = false;
            Logger.logInfo("Default iOS plist generated in " + genPlist.toString() + ".\n" +
                    "Consider copying it to " + rootPath.toString() + " before performing any modification");
        }

        Path userAssets = rootPath.resolve(Constants.IOS_ASSETS_FOLDER);
        if (!Files.exists(userAssets) || !(Files.isDirectory(userAssets) && Files.list(userAssets).count() > 0)) {
            // copy assets to gensrc/ios
            Path iosPath = paths.getGenPath().resolve(sourceOS);
            Path iosAssets = iosPath.resolve(Constants.IOS_ASSETS_FOLDER);
            InfoPlist.assets.forEach(a -> {
                try {
                    FileOps.copyResource("/native/ios/assets/" + a, iosAssets.resolve(a));
                } catch (IOException e) {
                    Logger.logFatal(e, "Error copying resource " + a + ": " + e.getMessage());
                }
            });
            iconAssets.forEach(a -> {
                try {
                    FileOps.copyResource("/native/ios/assets/Assets.xcassets/AppIcon.appiconset/" + a,
                            iosAssets.resolve("Assets.xcassets").resolve("AppIcon.appiconset").resolve(a));
                } catch (IOException e) {
                    Logger.logFatal(e, "Error copying resource " + a + ": " + e.getMessage());
                }
            });
            FileOps.copyResource("/native/ios/assets/Base.lproj/LaunchScreen.storyboard",
                    iosAssets.resolve("Base.lproj").resolve("LaunchScreen.storyboard"));
            FileOps.copyResource("/native/ios/assets/Base.lproj/MainScreen.storyboard",
                    iosAssets.resolve("Base.lproj").resolve("MainScreen.storyboard"));
            copyVerifyBase(iosAssets.resolve("Base.lproj"));

            FileOps.copyResource("/native/ios/assets/Assets.xcassets/Contents.json",
                    iosAssets.resolve("Assets.xcassets").resolve("Contents.json"));
            copyVerifyAssets(iosAssets);
            Logger.logInfo("Default iOS resources generated in " + iosPath.toString() + ".\n" +
                    "Consider copying them to " + rootPath.toString() + " before performing any modification");
        } else {
            copyVerifyBase(userAssets.resolve("Base.lproj"));
            copyVerifyAssets(userAssets);
            copyOtherAssets(userAssets);
        }

        Path plist = getPlistPath(paths, sourceOS);
        if (plist == null) {
            throw new IOException("Error: plist not found");
        }

        Path executable = appPath.resolve(executableName);
        if (!Files.exists(executable)) {
            String errorMessage = "The executable " + executable + " doesn't exist.";
            if (!appName.equals(executableName) && Files.exists(appPath.resolve(appName))) {
                errorMessage += "\nMake sure the CFBundleExecutable key in the " + plist.toString() + " file is set to: " + appName;
            }
            throw new IOException(errorMessage);
        }
        if (!Files.isExecutable(executable)) {
            throw new IOException("The file " + executable + " is not executable.");
        }

        copyPartialPlistFiles();

        try {
            NSDictionaryEx dict = new NSDictionaryEx(plist.toFile());
            if (!inited) {
                dict.put("CFBundleIdentifier", bundleIdName);
                dict.put("CFBundleExecutable", executableName);
                dict.put("CFBundleName", bundleName);
                dict.put("CFBundleVersion", bundleVersion);
                dict.put("CFBundleShortVersionString", bundleShortVersion);
                dict.saveAsXML(plist);
            } else {
                boolean modified = false;
                if (!bundleName.equals(appName) && !bundleName.equals(dict.get("CFBundleName").toString())) {
                    dict.put("CFBundleName", bundleName);
                    modified = true;
                }
                if (!bundleVersion.equals(DEFAULT_BUNDLE_VERSION) && !bundleVersion.equals(dict.get("CFBundleVersion").toString())) {
                    dict.put("CFBundleVersion", bundleVersion);
                    modified = true;
                }
                if (!bundleShortVersion.equals(DEFAULT_BUNDLE_VERSION) && !bundleShortVersion.equals(dict.get("CFBundleShortVersionString").toString())) {
                    dict.put("CFBundleShortVersionString", bundleShortVersion);
                    modified = true;
                }
                if (modified) {
                    Logger.logDebug("Updating " + plist.toString() + " with new values from releaseConfiguration");
                    dict.saveAsXML(plist);
                }
            }
            dict.put("DTPlatformName", xcodeUtil.getPlatformName());
            dict.put("DTSDKName", xcodeUtil.getSDKName());
            dict.put("MinimumOSVersion", "11.0");
            dict.put("CFBundleSupportedPlatforms", new NSArray(new NSString(sdk.getSdkName())));
            dict.put("DTPlatformVersion", xcodeUtil.getPlatformVersion());
            dict.put("DTPlatformBuild", xcodeUtil.getPlatformBuild());
            dict.put("DTSDKBuild", xcodeUtil.getPlatformBuild());
            dict.put("DTXcode", xcodeUtil.getDTXcode());
            dict.put("DTXcodeBuild", xcodeUtil.getDTXcodeBuild());
            dict.put("BuildMachineOSBuild", xcodeUtil.getBuildMachineOSBuild());
            NSDictionaryEx orderedDict = new NSDictionaryEx();
            orderedDict.put("CFBundleVersion", dict.get("CFBundleVersion"));
            dict.remove("CFBundleVersion");
            dict.getKeySet().forEach(k -> orderedDict.put(k, dict.get(k)));

            if (partialPListDir != null) {
                Files.walk(partialPListDir, 1)
                        .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".plist"))
                        .sorted((p1, p2) -> {
                            // classes.plist should be the last one, to override previous plist files
                            if (p1.toString().endsWith("classes_" + PARTIAL_PLIST_FILE)) {
                                return 1;
                            } else if (p2.toString().endsWith("classes_" + PARTIAL_PLIST_FILE)) {
                                return -1;
                            }
                            return p1.compareTo(p2);
                        })
                        .forEach(path -> {
                            try {
                                NSDictionary d = (NSDictionary) PropertyListParser.parse(path.toFile());
                                d.keySet().forEach(k -> orderedDict.put(k, d.get(k)));
                            } catch (Exception e) {
                                Logger.logFatal(e, "Error parsing plist file: " + path);
                            }
                        });
            }
            orderedDict.put("MinimumOSVersion", minOSVersion != null ? minOSVersion : "11.0");

            //             BinaryPropertyListWriter.write(new File(appDir, "Info.plist"), orderedDict);
            orderedDict.saveAsBinary(appPath.resolve("Info.plist"));
            orderedDict.saveAsXML(tmpPath.resolve("Info.plist"));
            orderedDict.getEntrySet().forEach(e -> {
                        if ("CFBundleIdentifier".equals(e.getKey())) {
                            Logger.logDebug("Bundle ID = "+e.getValue().toString());
                            bundleId = e.getValue().toString();
                        }
                        Logger.logDebug("Info.plist Entry: " + e);
                    }
            );
            return plist;
        } catch (Exception ex) {
            Logger.logFatal(ex, "Could not process property list");
        }
        return null;
    }

    static Path getPlistPath(ProcessPaths paths, String sourceName) {
        Path userPlist = Objects.requireNonNull(paths).getSourcePath()
                .resolve(Objects.requireNonNull(sourceName)).resolve(Constants.PLIST_FILE);
        if (Files.exists(userPlist)) {
            return userPlist;
        }
        Path genPlist = paths.getGenPath().resolve(sourceName).resolve(Constants.PLIST_FILE);
        if (Files.exists(genPlist)) {
            return genPlist;
        }
        return null;
    }

    private String getExecutableName(String appName, String sourceName) {
        Path plist = getPlistPath(paths, sourceName);
        if (plist == null) {
            return appName;
        }

        try {
            NSDictionaryEx dict = new NSDictionaryEx(plist.toFile());
            return dict.getEntrySet().stream()
                    .filter(e -> "CFBundleExecutable".equals(e.getKey()))
                    .findFirst()
                    .map(e -> {
                        Logger.logDebug("Executable Name = " + e.getValue().toString());
                        return e.getValue().toString();
                    })
                    .orElseThrow(() -> new RuntimeException("CFBundleExecutable key was not found in plist file " + plist.toString()));
        } catch (Exception ex) {
            Logger.logFatal(ex, "Could not process CFBundleExecutable");
        }

        Logger.logSevere("Error: ExecutableName was found");
        throw new RuntimeException("No executable name was found.\n " +
                "Please check the src/ios/Default-info.plist file and make sure CFBundleExecutable key exists");
    }

    public static String getBundleId(Path plist, String appId) {
        if (plist == null) {
            Objects.requireNonNull(appId, "AppId can't be null if plist is not provided");
            return appId;
        }

        try {
            NSDictionaryEx dict = new NSDictionaryEx(plist.toFile());
            return dict.getEntrySet().stream()
                    .filter(e -> e.getKey().equals("CFBundleIdentifier"))
                    .findFirst()
                    .map(e -> {
                        Logger.logDebug("Got Bundle ID = " + e.getValue().toString());
                        return e.getValue().toString();
                    })
                    .orElseThrow(() -> new RuntimeException("CFBundleIdentifier key was not found in plist file " + plist.toString()));
        } catch (Exception ex) {
            Logger.logFatal(ex, "Could not process CFBundleIdentifier");
        }

        Logger.logSevere("Error: no bundleId was found");
        throw new RuntimeException("No bundleId was found.\n " +
                "Please check the src/ios/Default-info.plist file and make sure CFBundleIdentifier key exists");
    }

    /**
     * Walks through the classes jar and other dependency jars files in the classpath,
     * and looks for META-INF/substrate/ios/Partial-Info.plist files.
     *
     * The method will copy all the plist files found into the partial plist folder
     *
     * @throws IOException
     */
    private void copyPartialPlistFiles() throws IOException, InterruptedException {
        if (!Files.exists(partialPListDir)) {
            Files.createDirectories(partialPListDir);
        }

        Logger.logDebug("Scanning for plist files");
        final List<File> jars = new ClassPath(projectConfiguration.getClasspath()).getJars(true);
        String prefix = META_INF_SUBSTRATE_IOS + PARTIAL_PLIST_FILE;
        for (File jar : jars) {
            try (ZipFile zip = new ZipFile(jar)) {
                Logger.logDebug("Scanning " + jar);
                for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                    ZipEntry zipEntry = e.nextElement();
                    String name = zipEntry.getName();
                    if (!zipEntry.isDirectory() && name.equals(prefix)) {
                        String jarName = jar.getName().substring(0, jar.getName().lastIndexOf(".jar"));
                        Path classPath = partialPListDir.resolve(jarName + "_" + PARTIAL_PLIST_FILE);
                        Logger.logDebug("Adding plist from " + zip.getName() + " :: " + name + " into " + classPath);
                        FileOps.copyStream(zip.getInputStream(zipEntry), classPath);
                    }
                }
            } catch (IOException e) {
                throw new IOException("Error processing partial plist files from jar: " + jar + ": " + e.getMessage() + ", " + Arrays.toString(e.getSuppressed()));
            }
        }
    }

    private void copyVerifyAssets(Path resourcePath) throws IOException {
        if (resourcePath == null || !Files.exists(resourcePath)) {
            throw new RuntimeException("Error: invalid path " + resourcePath);
        }
        if (minOSVersion == null) {
            minOSVersion = "11.0";
        }
        Files.walk(resourcePath, 1).forEach(p -> {
            if (Files.isDirectory(p)) {
                if (p.toString().endsWith(".xcassets")) {
                    try {
                        Logger.logDebug("Calling verifyAssetCatalog for " + p.toString());
                        verifyAssetCatalog(p, sdk.name().toLowerCase(Locale.ROOT),
                                minOSVersion,
                                Arrays.asList("iphone", "ipad"), "");
                    } catch (Exception ex) {
                        Logger.logFatal(ex, "Failed creating directory " + p);
                    }
                }
            } else {
                Path targetPath = appPath.resolve(resourcePath.relativize(p));
                FileOps.copyFile(p, targetPath);
            }
        });
    }

    private void verifyAssetCatalog(Path resourcePath, String platform, String minOSVersion, List<String> devices, String output) throws Exception {
        List<String> commandsList = new ArrayList<>();
        commandsList.add("--output-format");
        commandsList.add("human-readable-text");

        final String appIconSet = ".appiconset";
        final String launchImage = ".launchimage";

        File inputDir = resourcePath.toFile();
        File outputDir = new File(appPath.toFile(), output);
        Files.createDirectories(outputDir.toPath());
        Files.walk(resourcePath).forEach(p -> {
            if (Files.isDirectory(p) && p.toString().endsWith(appIconSet)) {
                String appIconSetName = p.getFileName().toString()
                        .substring(0, p.getFileName().toString().length() - appIconSet.length());
                commandsList.add("--app-icon");
                commandsList.add(appIconSetName);
            } else if (Files.isDirectory(p) && p.toString().endsWith(launchImage)) {
                String launchImagesName = p.getFileName().toString()
                        .substring(0, p.getFileName().toString().length() - launchImage.length());
                commandsList.add("--launch-image");
                commandsList.add(launchImagesName);
            }
        });

        if (Files.exists(partialPListDir)) {
            try {
                Files.walk(partialPListDir).forEach(f -> f.toFile().delete());
            } catch (IOException ex) {
                Logger.logSevere("Error removing files from " + partialPListDir.toString() + ": " + ex);
            }
        }
        try {
            Files.createDirectories(partialPListDir);
        } catch (IOException ex) {
            Logger.logSevere("Error creating " + partialPListDir.toString() + ": " + ex);
        }

        File partialInfoPlist = File.createTempFile(resourcePath.getFileName().toString() + "_", ".plist", partialPListDir.toFile());

        commandsList.add("--output-partial-info-plist");
        commandsList.add(partialInfoPlist.toString());

        commandsList.add("--platform");
        commandsList.add(platform);

        String actoolForSdk = XcodeUtils.getCommandForSdk("actool", platform);
        commandsList.add("--minimum-deployment-target");
        commandsList.add(minOSVersion);
        devices.forEach(device -> {
            commandsList.add("--target-device");
            commandsList.add(device);
        });

        ProcessRunner args = new ProcessRunner(actoolForSdk);
        args.addArgs(commandsList);
        args.addArgs("--compress-pngs", "--compile", outputDir.toString(), inputDir.toString());
        int result = args.runProcess("actool");
        if (result != 0) {
            throw new RuntimeException("Error verifyAssetCatalog");
        }
    }

    /**
     * Scans the src/ios/assets folder for possible folders other than
     * Assets.cassets (which is compressed with actool) or Base.lproj
     * (which is compiled with ibtool) and copy them directly to the
     * app folder
     * @param resourcePath the path for ios assets
     * @throws IOException
     */
    private void copyOtherAssets(Path resourcePath) throws IOException {
        if (resourcePath == null || !Files.exists(resourcePath)) {
            throw new RuntimeException("Error: invalid path " + resourcePath);
        }
        List<Path> otherAssets = Files.list(resourcePath)
                .filter(r -> Files.isDirectory(r))
                .filter(r -> !r.toString().endsWith("Assets.xcassets") &&
                        !r.toString().endsWith("Base.lproj"))
                .collect(Collectors.toList());
        for (Path assetPath : otherAssets) {
            Path targetPath = appPath.resolve(resourcePath.relativize(assetPath));
            if (Files.exists(targetPath)) {
                FileOps.deleteDirectory(targetPath);
            }
            Logger.logDebug("Copying directory " + assetPath);
            FileOps.copyDirectory(assetPath, targetPath);
        }
    }

    private void copyVerifyBase(Path resourcePath) throws IOException {
        Objects.requireNonNull(resourcePath, "Error: invalid path for Base.lproj");
        if (!Files.exists(resourcePath)) {
            Logger.logInfo("Screen storyboards not found. Adding default ones at path: " + resourcePath.toString());
            Path userPath = Files.createDirectories(resourcePath);
            FileOps.copyResource("/native/ios/assets/Base.lproj/LaunchScreen.storyboard",
                    userPath.resolve("LaunchScreen.storyboard"));
            FileOps.copyResource("/native/ios/assets/Base.lproj/MainScreen.storyboard",
                    userPath.resolve("MainScreen.storyboard"));
        }
        if (minOSVersion == null) {
            minOSVersion = "11.0";
        }
        tmpStoryboardsDir = tmpPath.resolve("storyboards");
        if (Files.exists(tmpStoryboardsDir)) {
            try {
                Files.walk(tmpStoryboardsDir).forEach(f -> f.toFile().delete());
            } catch (IOException ex) {
                Logger.logSevere("Error removing files from " + tmpStoryboardsDir.toString() + ": " + ex);
            }
        }
        try {
            Files.createDirectories(tmpStoryboardsDir);
        } catch (IOException ex) {
            Logger.logSevere("Error creating " + tmpStoryboardsDir.toString() + ": " + ex);
        }

        Files.walk(resourcePath, 1).forEach(p -> {
            if (!Files.isDirectory(p) && p.toString().endsWith(".storyboard")) {
                try {
                    Logger.logDebug("Calling verifyStoryBoard for " + p.toString());
                    verifyStoryBoard(p, sdk.name().toLowerCase(Locale.ROOT),
                            minOSVersion,
                            Arrays.asList("iphone", "ipad"), "Base.lproj");
                } catch (Exception ex) {
                    Logger.logFatal(ex, "Failed creating directory " + p);
                }
            }
        });
        try {
            linkStoryBoards(resourcePath, sdk.name().toLowerCase(Locale.ROOT),
                    minOSVersion,
                    Arrays.asList("iphone", "ipad"), "");
        } catch (Exception ex) {
            Logger.logFatal(ex, "Failed linking storyboards " + ex.getMessage());
        }
    }

    private void verifyStoryBoard(Path resourcePath, String platform, String minOSVersion, List<String> devices, String output) throws Exception {
        List<String> commandsList = new ArrayList<>();
        commandsList.add("--output-format");
        commandsList.add("human-readable-text");

        File inputDir = resourcePath.toFile();
        File outputDir = new File(tmpStoryboardsDir.toFile(), output);
        Files.createDirectories(outputDir.toPath());

        File partialInfoPlist = File.createTempFile(resourcePath.getFileName().toString() + "_", ".plist", tmpStoryboardsDir.toFile());

        commandsList.add("--output-partial-info-plist");
        commandsList.add(partialInfoPlist.toString());

        String ibtoolForSdk = XcodeUtils.getCommandForSdk("ibtool", platform);
        commandsList.add("--minimum-deployment-target");
        commandsList.add(minOSVersion);
        devices.forEach(device -> {
            commandsList.add("--target-device");
            commandsList.add(device);
        });

        ProcessRunner args = new ProcessRunner(ibtoolForSdk);
        args.addArgs(commandsList);
        args.addArgs("--compilation-directory", outputDir.toString(), inputDir.toString());
        int result = args.runProcess("ibtool");
        if (result != 0) {
            throw new RuntimeException("Error verifyStoryBoard");
        }
    }

    private void linkStoryBoards(Path resourcePath, String platform, String minOSVersion, List<String> devices, String output) throws Exception {
        List<String> commandsList = new ArrayList<>();
        commandsList.add("--output-format");
        commandsList.add("human-readable-text");

        File outputDir = new File(appPath.toFile(), output);

        String ibtoolForSdk = XcodeUtils.getCommandForSdk("ibtool", platform);
        commandsList.add("--minimum-deployment-target");
        commandsList.add(minOSVersion);
        devices.forEach(device -> {
            commandsList.add("--target-device");
            commandsList.add(device);
        });

        ProcessRunner args = new ProcessRunner(ibtoolForSdk);
        args.addArgs(commandsList);
        args.addArgs("--link", outputDir.toString());
        try {
            Files.walk(tmpStoryboardsDir.resolve("Base.lproj"), 1).forEach(p -> {
                if (Files.isDirectory(p) && p.toString().endsWith(".storyboardc")) {
                    args.addArg(p.toString());
                }
            });
        } catch (IOException ex) {
            Logger.logSevere("Error linking files from " + appPath.resolve("Base.lproj").toString() + ": " + ex);
        }
        int result = args.runProcess("ibtool");
        if (result != 0) {
            throw new RuntimeException("Error linkStoryBoards");
        }
    }
}
