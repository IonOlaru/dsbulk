/*
 * Copyright DataStax, Inc.
 *
 * This software is subject to the below license agreement.
 * DataStax may make changes to the agreement from time to time,
 * and will post the amended terms at
 * https://www.datastax.com/terms/datastax-dse-bulk-utility-license-terms.
 */
package com.datastax.dsbulk.commons.internal.config;

import com.datastax.dsbulk.commons.config.BulkConfigurationException;
import com.datastax.dsbulk.commons.internal.reflection.ReflectionUtils;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigException.Missing;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

public class ConfigUtils {

  private static final Pattern THREADS_PATTERN =
      Pattern.compile("(.+)\\s*C", Pattern.CASE_INSENSITIVE);

  @Nullable private static final URL CURRENT_DIR;

  @Nullable private static final Path USER_HOME;

  private static final String TYPE_ANNOTATION = "@type";

  private static final String LEAF_ANNOTATION = "@leaf";

  static {
    URL currentDir;
    try {
      currentDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().toUri().toURL();
    } catch (MalformedURLException e) {
      currentDir = null;
    }
    CURRENT_DIR = currentDir;
    Path userHome;
    try {
      userHome = Paths.get(System.getProperty("user.home")).toAbsolutePath();
    } catch (InvalidPathException e) {
      userHome = null;
    }
    USER_HOME = userHome;
  }

  /**
   * Invalidates caches and creates a resolved config containing only the driver settings that
   * DSBulk overrides.
   *
   * <p>The reference config is obtained from all classpath resources named driver-reference.conf.
   *
   * <p>This method is only useful for documentation purposes.
   *
   * @return a resolved reference config containing only the driver settings that DSBulk overrides.
   */
  public static Config standaloneDriverReference() {
    ConfigFactory.invalidateCaches();
    return ConfigFactory.parseResourcesAnySyntax("driver-reference").resolve();
  }

  /**
   * Invalidates caches and creates a resolved reference config for DSBulk.
   *
   * <p>The reference config is obtained from the following stack:
   *
   * <ol>
   *   <li>All classpath resources named dsbulk-reference.conf: DSBulk specific settings and driver
   *       overrides.
   *   <li>All classpath resources named dse-reference.conf: DSE driver specific settings.
   *   <li>All classpath resources named reference.conf: OSS driver settings.
   * </ol>
   *
   * @return a resolved reference config for DSBulk
   */
  @NonNull
  public static Config createReferenceConfig() {
    // parse errors should not happen here
    return ConfigFactory.parseResourcesAnySyntax("dsbulk-reference")
        .withFallback(ConfigFactory.parseResourcesAnySyntax("dse-reference"))
        .withFallback(ConfigFactory.defaultReference())
        .resolve();
  }

  /**
   * Invalidates caches and creates a resolved application config for DSBulk, optionally pulling
   * application settings from the given alternate location.
   *
   * <p>The application config is obtained from the following stack:
   *
   * <ol>
   *   <li>All classpath resources named application[.conf,.json,.properties] or <code>appConfigPath
   *       </code> if non null: application settings (DSBulk and driver overrides).
   *   <li>dsbulk-reference.conf: DSBulk specific settings and driver overrides.
   *   <li>dse-reference.conf: DSE driver specific settings.
   *   <li>reference.conf: OSS driver settings.
   * </ol>
   *
   * * @param appConfigPath An alternate location for the application settings, or null to use the
   * default application resources.
   *
   * @return a resolved application config for DSBulk
   */
  @NonNull
  public static Config createApplicationConfig(@Nullable Path appConfigPath) {
    try {
      if (appConfigPath != null) {
        // If the user specified the -f option (giving us an app config path),
        // set the config.file property to tell TypeSafeConfig.
        System.setProperty("config.file", appConfigPath.toString());
      }
      Config referenceConfig = createReferenceConfig();
      return ConfigFactory.defaultOverrides()
          .withFallback(ConfigFactory.defaultApplication())
          .withFallback(referenceConfig)
          .resolve();
    } catch (ConfigException.Parse e) {
      throw new IllegalArgumentException(
          String.format(
              "Error parsing configuration file %s at line %s. "
                  + "Please make sure its format is compliant with HOCON syntax. "
                  + "If you are using \\ (backslash) to define a path, "
                  + "escape it with \\\\ or use / (forward slash) instead.",
              e.origin().filename(), e.origin().lineNumber()),
          e);
    }
  }

  /**
   * Returns the {@link Path} object at the given path.
   *
   * <p>The returned Path is normalized and absolute.
   *
   * <p>For convenience, if the path begins with a tilde (`~`), that symbol will be expanded to the
   * current user's home directory, as supplied by `System.getProperty("user.home")`. Note that this
   * expansion will not occur when the tilde is not the first character in the path, nor when the
   * home directory owner is not the current user.
   *
   * @param config The {@link Config} to use.
   * @param path path expression.
   * @return the Path object at the requested path.
   * @throws Missing if value is absent or null.
   * @throws ConfigException.WrongType if value is not convertible to a Path.
   */
  public static Path getPath(Config config, String path) {
    String setting = config.getString(path);
    try {
      return resolvePath(setting);
    } catch (InvalidPathException e) {
      throw new ConfigException.WrongType(
          config.origin(),
          String.format("%s: Expecting valid filepath, got '%s'", path, setting),
          e);
    }
  }

  /**
   * Returns the {@link URL} object at the given path.
   *
   * <p>The value will be first interpreted directly as a URL; if the parsing fails, the value will
   * be then interpreted as a path on the local filesystem, then converted to a file URL.
   *
   * <p>If the value is "-" map it to "std:/", to indicate this url represents stdout (when
   * unloading) and stdin (when loading).
   *
   * <p>The returned URL is normalized and absolute.
   *
   * @param config The {@link Config} to use.
   * @param path path expression.
   * @return the URL object at the requested path.
   * @throws Missing if value is absent or null.
   * @throws ConfigException.WrongType if value is not convertible to a URL.
   */
  public static URL getURL(Config config, String path) {
    String setting = config.getString(path);
    try {
      return resolveURL(setting);
    } catch (Exception e) {
      throw new ConfigException.WrongType(
          config.origin(),
          String.format("%s: Expecting valid filepath or URL, got '%s'", path, setting),
          e);
    }
  }

  /**
   * Returns the number of threads at the given path.
   *
   * <p>The given path can be an integer, or alternatively, an integer followed by the letter C, in
   * which case, the resulting value is the integer multiplied by the number of available cores on
   * the system.
   *
   * @param config The {@link Config} to use.
   * @param path path expression.
   * @return the number of threads at the requested path.
   * @throws Missing if value is absent or null.
   * @throws ConfigException.WrongType if value is not convertible to a number of threads.
   */
  public static int getThreads(Config config, String path) {
    String setting = config.getString(path);
    try {
      return resolveThreads(setting);
    } catch (Exception e) {
      throw new ConfigException.WrongType(
          config.origin(),
          String.format("%s: Expecting integer or string in 'nC' syntax, got '%s'", path, setting),
          e);
    }
  }

  /**
   * Returns the character at the given path.
   *
   * @param config The {@link Config} to use.
   * @param path path expression.
   * @return the character at the requested path.
   * @throws Missing if value is absent or null.
   * @throws ConfigException.WrongType if value is not convertible to a single character.
   */
  public static char getChar(Config config, String path) {
    String setting = config.getString(path);
    if (setting.length() != 1) {
      throw new ConfigException.WrongType(
          config.origin(), String.format("%s: Expecting single char, got '%s'", path, setting));
    }
    return setting.charAt(0);
  }

  /**
   * Returns the {@link Charset} at the given path.
   *
   * @param config The {@link Config} to use.
   * @param path path expression.
   * @return the Charset at the requested path.
   * @throws Missing if value is absent or null.
   * @throws ConfigException.WrongType if value is not convertible to a Charset.
   */
  public static Charset getCharset(Config config, String path) {
    String setting = config.getString(path);
    try {
      return Charset.forName(setting);
    } catch (Exception e) {
      throw new ConfigException.WrongType(
          config.origin(),
          String.format("%s: Expecting valid charset name, got '%s'", path, setting),
          e);
    }
  }

  /**
   * Resolves the given path.
   *
   * <p>The returned path is normalized and absolute. If the input denotes a relative path, it is
   * resolved against the current working directory. If it starts with a tilde, the tilde is
   * expanded into the current user's home directory.
   *
   * @param path The path to resolve.
   * @return The resolved {@link Path}, absolute and normalized.
   * @throws InvalidPathException If the path cannot be resolved.
   */
  @NonNull
  public static Path resolvePath(@NonNull String path) throws InvalidPathException {
    Optional<Path> resolved = resolveUserHome(path);
    return resolved.orElseGet(() -> Paths.get(path).toAbsolutePath().normalize());
  }

  /**
   * Resolves the given URL.
   *
   * <p>The returned URL is normalized.
   *
   * <p>This method first tries to interpret the input as a valid URL, possibly expanding the
   * special {@code "-"} (single dash) URL into DSBulk's internal {@link
   * com.datastax.dsbulk.commons.url.LoaderURLStreamHandlerFactory#STD standard input/output} URL.
   *
   * <p>If that fails, this method then attempts to interpret the input as a path object. See {@link
   * #resolvePath(String)}.
   *
   * @param url The URL to resolve.
   * @return The resolved {@link URL}, normalized.
   * @throws MalformedURLException If the URL cannot be resolved.
   * @throws InvalidPathException If the path cannot be resolved.
   */
  @NonNull
  public static URL resolveURL(@NonNull String url)
      throws MalformedURLException, InvalidPathException {
    if (url.equals("-")) {
      url = "std:/";
    }
    Optional<Path> resolved = resolveUserHome(url);
    try {
      URL u;
      if (resolved.isPresent()) {
        u = resolved.get().toUri().toURL();
      } else if (CURRENT_DIR == null) {
        u = new URL(url);
      } else {
        // This helps normalize relative URLs
        u = new URL(CURRENT_DIR, url);
      }
      return u.toURI().normalize().toURL();
    } catch (Exception e) {
      // not a valid URL, consider it a path on the local filesystem.
      try {
        return resolvePath(url).toUri().toURL();
      } catch (MalformedURLException | InvalidPathException e1) {
        e1.addSuppressed(e);
        throw e1;
      }
    }
  }

  /**
   * Resolves a path starting with "~" against the current user's home directory. Returns empty if
   * the path is not relative to the user home directory.
   *
   * <p>Resolving against another user's home directory is not supported and throws {@link
   * InvalidPathException}.
   *
   * @param path The path to resolve.
   * @return The resolved path, or empty if it is not relative to the user home directory.
   * @throws InvalidPathException if the path string cannot be converted to a Path, or if the path
   *     references another user's home directory.
   */
  @NonNull
  public static Optional<Path> resolveUserHome(@NonNull String path) {
    if (USER_HOME != null && path.startsWith("~")) {
      if (path.equals("~") || path.startsWith("~/")) {
        Path resolved = USER_HOME.resolve('.' + path.substring(1)).toAbsolutePath().normalize();
        return Optional.of(resolved);
      } else {
        // other home directories than the current user's are not supported, e.g. '~someuser/'
        throw new InvalidPathException(path, "Cannot resolve home directory", 1);
      }
    }
    return Optional.empty();
  }

  /**
   * Resolves the given input as a positive integer representing the number of threads to allocate.
   *
   * <p>This method first tries to parse the input directly as an integer.
   *
   * <p>If that fails, it then tries to parse the input as an integer followed by the letter 'C'. If
   * that succeeds, the total number of threads returned is <code> n * {@link
   * Runtime#availableProcessors() number of available cores}</code>.
   *
   * @param threadsStr The string to parse.
   * @return The number of threads.
   * @throws PatternSyntaxException If the input cannot be parsed.
   * @throws IllegalArgumentException If the input can be parsed, but the resulting integer is not
   *     positive.
   */
  public static int resolveThreads(@NonNull String threadsStr) {
    int threads;
    try {
      threads = Integer.parseInt(threadsStr);
    } catch (NumberFormatException e) {
      Matcher matcher = THREADS_PATTERN.matcher(threadsStr.trim());
      if (matcher.matches()) {
        threads =
            (int)
                (((float) Runtime.getRuntime().availableProcessors())
                    * Float.parseFloat(matcher.group(1)));
        return Math.max(1, threads);
      } else {
        PatternSyntaxException e1 =
            new PatternSyntaxException(
                "Cannot parse input as N * <num_cores>", THREADS_PATTERN.pattern(), 0);
        e1.addSuppressed(e);
        throw e1;
      }
    }
    if (threads < 1) {
      throw new IllegalArgumentException("Expecting positive number of threads, got " + threads);
    }
    return threads;
  }

  /**
   * Returns a string representation of the value type at this path. This is mostly intended for
   * inclusion in generated documentation.
   *
   * @param config the config.
   * @param path path expression.
   * @return the type string
   * @throws ConfigException.Missing if value is absent.
   */
  @NonNull
  public static Optional<String> getTypeString(@NonNull Config config, @NonNull String path) {
    ConfigValue value = getNullSafeValue(config, path);
    Optional<String> typeHint = getTypeHint(value);
    if (typeHint.isPresent()) {
      return typeHint;
    }
    ConfigValueType type = value.valueType();
    if (type == ConfigValueType.LIST) {
      ConfigList list = config.getList(path);
      if (list.isEmpty()) {
        return Optional.of("list");
      } else {
        ConfigValueType elementType = list.get(0).valueType();
        return getTypeString(elementType).map(str -> "list<" + str + ">");
      }
    } else if (type == ConfigValueType.OBJECT) {
      ConfigObject object = config.getObject(path);
      if (object.isEmpty()) {
        return Optional.of("map");
      } else {
        ConfigValueType valueType = object.values().iterator().next().valueType();
        return getTypeString(valueType).map(str -> "map<string," + str + ">");
      }
    } else {
      return getTypeString(type);
    }
  }

  /**
   * Alternative to {@link ConfigValue#valueType()} that honors any type hints found in the
   * configuration, if any.
   *
   * @param config the config.
   * @param path path expression.
   * @return the {@link ConfigValueType value type}.
   * @throws ConfigException.Missing if value is absent.
   */
  @NonNull
  public static ConfigValueType getValueType(@NonNull Config config, @NonNull String path) {
    ConfigValue value = getNullSafeValue(config, path);
    Optional<String> typeHint = getTypeHint(value);
    if (typeHint.isPresent()) {
      String hint = typeHint.get();
      if (hint.equals("string")) {
        return ConfigValueType.STRING;
      }
      if (hint.equals("number")) {
        return ConfigValueType.NUMBER;
      }
      if (hint.equals("boolean")) {
        return ConfigValueType.BOOLEAN;
      }
      if (hint.startsWith("list")) {
        return ConfigValueType.LIST;
      }
      if (hint.startsWith("map")) {
        return ConfigValueType.OBJECT;
      }
    }
    return value.valueType();
  }

  /**
   * Retrieves the type hint for the given value, if any.
   *
   * @param value the {@link ConfigValue value} to inspect.
   * @return The type hint, if any, or empty otherwise.
   */
  @NonNull
  public static Optional<String> getTypeHint(@NonNull ConfigValue value) {
    return value.origin().comments().stream()
        .filter(ConfigUtils::isTypeHint)
        .map(line -> line.replace("@type", ""))
        .map(String::trim)
        .findFirst();
  }

  /**
   * Returns the comments associated with the given value, excluding type hints.
   *
   * @param value the {@link ConfigValue value} to inspect.
   * @return The comments associated with the given value
   */
  @NonNull
  public static String getComments(@NonNull ConfigValue value) {
    return value.origin().comments().stream()
        .filter(line -> !isTypeHint(line))
        .filter(line -> !isLeaf(line))
        .map(String::trim)
        .collect(Collectors.joining("\n"));
  }

  /**
   * Checks the given line for the presence of an @type annotation.
   *
   * @param line The line to inspect.
   * @return Returns true if the given line contains a type annotation, false otherwise.
   */
  public static boolean isTypeHint(@NonNull String line) {
    return line.contains(TYPE_ANNOTATION);
  }

  /**
   * Returns a string representation of the given value type, or empty if none found. This is mostly
   * intended for inclusion in generated documentation.
   *
   * @param type ConfigValueType to stringify.
   * @return the type string
   */
  @NonNull
  private static Optional<String> getTypeString(@NonNull ConfigValueType type) {
    switch (type) {
      case STRING:
        return Optional.of("string");
      case LIST:
        return Optional.of("list");
      case OBJECT:
        return Optional.of("map");
      case NUMBER:
        return Optional.of("number");
      case BOOLEAN:
        return Optional.of("boolean");
      case NULL:
      default:
        return Optional.empty();
    }
  }

  /**
   * Returns true if the given value is a leaf.
   *
   * <p>Leaf values are values of all types except OBJECT, or values of type OBJECT explicitly
   * annotated with @leaf.
   *
   * @param value The value to inspect.
   * @return True if the value is a leaf, false otherwise.
   */
  public static boolean isLeaf(@NonNull ConfigValue value) {
    return !(value instanceof ConfigObject)
        || value.origin().comments().stream().anyMatch(ConfigUtils::isLeaf);
  }

  /**
   * Checks the given line for the presence of an @leaf annotation.
   *
   * @param line The line to inspect.
   * @return Returns true if the given line contains a leaf annotation, false otherwise.
   */
  public static boolean isLeaf(@NonNull String line) {
    return line.contains(LEAF_ANNOTATION);
  }

  /**
   * An alternative to {@link Config#getValue(String)} that handles null values gracefully instead
   * of throwing.
   *
   * <p>Note that the path must still exist; if the path does not exist (i.e., the value is
   * completely absent from the config object), this method still throws {@link Missing}.
   *
   * @param config The config object to get the value from.
   * @param path The path at which the value is to be found.
   * @return The {@link ConfigValue value}.
   * @throws Missing If the path is not present in the config object.
   */
  @NonNull
  public static ConfigValue getNullSafeValue(@NonNull Config config, @NonNull String path) {
    int dot = path.indexOf('.');
    if (dot == -1) {
      ConfigValue value = config.root().get(path);
      if (value == null) {
        throw new Missing(path);
      }
      return value;
    } else {
      try {
        return getNullSafeValue(config.getConfig(path.substring(0, dot)), path.substring(dot + 1));
      } catch (Missing e) {
        throw new Missing(path);
      }
    }
  }

  /**
   * Loads list of URLs from a file given as the urlfile argument using encoding. The given file
   * should be encoded in UTF_8.
   *
   * @param urlfile The path to file passed as the --urlfile argument to dsbulk.
   * @return The list of urls resolved from urlfile line by line.
   * @throws IOException If unable to load a file from urlfile path.
   */
  public static List<URL> getURLsFromFile(Path urlfile) throws IOException {
    List<URL> result = new ArrayList<>();
    List<String> paths = Files.readAllLines(urlfile);
    for (String path : paths) {
      try {
        if (!path.startsWith("#")) {
          result.add(ConfigUtils.resolveURL(path.trim()));
        }
      } catch (Exception e) {
        throw new BulkConfigurationException(
            String.format("%s: Expecting valid filepath or URL, got '%s'", urlfile, path), e);
      }
    }
    return result;
  }

  /**
   * Checks if the given path is present and its value is a non-empty string.
   *
   * @param config The config.
   * @param path The path expression.
   * @return {@code true} if the given path is present and its value is a non-empty string, {@code
   *     false} otherwise.
   */
  public static boolean isPathPresentAndNotEmpty(Config config, String path) {
    return config.hasPath(path) && !config.getString(path).isEmpty();
  }

  /**
   * Checks whether the given path has a default value or not.
   *
   * <p>A default value is the value defined in reference.conf.
   *
   * @param config The config.
   * @param path The path expression.
   * @return {@code true} if the given path has a default value, {@code false} otherwise.
   */
  public static boolean isValueFromReferenceConfig(Config config, String path) {
    if (!config.hasPathOrNull(path)) {
      return false;
    }
    ConfigValue value;
    if (config.getIsNull(path)) {
      value = getNullSafeValue(config, path);
    } else {
      value = config.getValue(path);
    }
    String resource = value.origin().resource();
    // Account for reference.conf and dsbulk-reference.conf
    return resource != null && resource.endsWith("reference.conf");
  }

  /**
   * Returns the {@link Class} object at the given path.
   *
   * <p>Short class names are allowed and will be resolved against common package names.
   *
   * @param <T> the expected type.
   * @param config The {@link Config} to use.
   * @param path path expression.
   * @param expected The expected class or interface that the object should be an instance of.
   * @return the Class object corresponding to the class name at the requested path.
   * @throws Missing if value is absent or null.
   * @throws ConfigException.WrongType if value is not convertible to a Path.
   * @throws ConfigException.BadValue if the object is not of the expected type.
   */
  public static <T> Class<? extends T> getClass(Config config, String path, Class<T> expected) {
    String setting = config.getString(path);
    try {
      Class<?> c = ReflectionUtils.resolveClass(setting);
      if (expected.isAssignableFrom(c)) {
        @SuppressWarnings("unchecked")
        Class<T> ret = (Class<T>) c;
        return ret;
      }
      throw new ConfigException.BadValue(
          config.origin(),
          path,
          String.format(
              "Class does not extend nor implement %s: %s",
              expected.getSimpleName(), c.getSimpleName()));
    } catch (Exception e) {
      throw new ConfigException.WrongType(
          config.origin(),
          String.format("%s: Expecting FQCN or short class name, got '%s'", path, setting),
          e);
    }
  }
}
