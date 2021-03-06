/*
 * Copyright (c) 2014 Hewlett-Packard Development Company, L.P.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package monasca.api.infrastructure.persistence.influxdb;

import org.joda.time.DateTime;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Utils {

  // Serie names match this pattern.
  private static final Pattern serieNamePattern = Pattern.compile("^.+\\?.+&.+(&.+=.+)*$");
  static final String COULD_NOT_LOOK_UP_COLUMNS_EXC_MSG = "Couldn't look up columns";

  private Utils() {
  }

  /**
   * InfluxDB Utilities for protecting against SQL injection attacks.
   */
  static class SQLSanitizer {

    private SQLSanitizer() {
    }

    private static final Pattern sqlUnsafePattern = Pattern.compile("^.*('|;)+.*$");

    static String sanitize(final String taintedString) throws Exception {
      Matcher m = sqlUnsafePattern.matcher(taintedString);
      if (m.matches()) {
        throw new Exception(String.format("Input from user contains single quote ['] or " +
                                          "semi-colon [;] characters[ %1$s ]", taintedString));
      }

      return taintedString;
    }
  }

  /**
   * InfluxDB Utilities for building parts of where clauses.
   */
  static class WhereClauseBuilder {

    private WhereClauseBuilder() {
    }

    static String buildTimePart(final DateTime startTime, final DateTime endTime) {
      final StringBuilder sb = new StringBuilder();

      if (startTime != null) {
        sb.append(String.format(" and time > %1$ds", startTime.getMillis() / 1000));
      }

      if (endTime != null) {
        sb.append(String.format(" and time < %1$ds", endTime.getMillis() / 1000));
      }

      return sb.toString();
    }

  }

  static String buildSerieNameRegex(final String tenantId, String region, final String name,
                                    final Map<String, String> dimensions) throws Exception {

    final StringBuilder regex = new StringBuilder("^");

    regex.append(urlEncodeUTF8(tenantId));
    regex.append("\\?");
    regex.append(urlEncodeUTF8(region));

    // Name is optional.
    if (name != null) {
      regex.append("&");
      regex.append(urlEncodeUTF8(name));
    }

    // Dimensions are optional.
    if (dimensions != null && !dimensions.isEmpty()) {

      // We depend on the fact that dimensions are sorted in the series name.
      final TreeSet<Dimension> dimSortedSet = buildSortedDimSet(dimensions);

      for (final Dimension dim : dimSortedSet) {
        regex.append(".*&");
        regex.append(urlEncodeUTF8(dim.name));
        regex.append("=");
        regex.append(urlEncodeUTF8(dim.value));
      }
    }
    return regex.toString();
  }

  static String urlEncodeUTF8(String s) throws Exception {
    return URLEncoder.encode(s, "UTF-8");
  }


  static String urlDecodeUTF8(final String s) throws UnsupportedEncodingException {
    return URLDecoder.decode(s, "UTF-8");
  }

  static TreeSet<Dimension> buildSortedDimSet(final Map<String, String> dimMap) {

    final TreeSet<Dimension> dimTreeSet = new TreeSet<>();
    for (final String dimName : dimMap.keySet()) {
      final Dimension dim = new Dimension(dimName, dimMap.get(dimName));
      dimTreeSet.add(dim);
    }
    return dimTreeSet;
  }

  static final class Dimension implements Comparable<Dimension> {

    final String name;
    final String value;

    private Dimension(final String name, final String value) {
      this.name = name;
      this.value = value;
    }

    @Override
    public int compareTo(Dimension o) {
      int nameCmp = String.CASE_INSENSITIVE_ORDER.compare(name, o.name);
      return (nameCmp != 0 ? nameCmp : String.CASE_INSENSITIVE_ORDER.compare(value, o.value));
    }
  }

  static class SerieNameDecoder {

    private final String serieName;
    private final String metricName;
    private final String tenantId;
    private final String region;
    private final Map<String, String> dimensions;

    SerieNameDecoder(final String serieName) throws Exception {

      if (!isSerieMetricName(serieName)) {
        throw new SerieNameDecodeException ("Serie name is not decodable: " + serieName);
      }

      this.serieName = serieName;

      this.tenantId = urlDecodeUTF8(serieName.substring(0, serieName.indexOf('?')));
      String rest = serieName.substring(serieName.indexOf('?') + 1);

      this.region = urlDecodeUTF8(rest.substring(0, rest.indexOf('&')));
      rest = rest.substring(rest.indexOf('&') + 1);

      if (rest.contains("&")) {
        this.metricName = urlDecodeUTF8(rest.substring(0, rest.indexOf('&')));
        rest = rest.substring(rest.indexOf('&') + 1);
      } else {
        this.metricName = urlDecodeUTF8(rest);
        rest = null;
      }

      // It's possible to have no dimensions.
      this.dimensions = new HashMap<>();
      while (rest != null) {
        final String nameValPair;
        if (rest.contains("&")) {
          nameValPair = rest.substring(0, rest.indexOf('&'));
          rest = rest.substring(rest.indexOf('&') + 1);
        } else {
          nameValPair = rest;
          rest = null;
        }
        final String dimName = urlDecodeUTF8(nameValPair.split("=")[0]);
        final String dimVal = urlDecodeUTF8(nameValPair.split("=")[1]);
        this.dimensions.put(dimName, dimVal);

      }
    }

    public String getSerieName() {
      return serieName;
    }

    public String getMetricName() {
      return metricName;
    }

    public String getTenantId() {
      return tenantId;
    }

    public String getRegion() {
      return region;
    }

    public Map<String, String> getDimensions() {
      return dimensions;
    }

  }

  static class SerieNameDecodeException extends Exception {
    private static final long serialVersionUID = 1L;

    public SerieNameDecodeException(String s) {
      super(s);
    }
  }

  /**
   * We might come across other series that are created by the persister or don't pertain to metric
   * data.  They will break the parsing. Throw them away.
   */

  static boolean isSerieMetricName(String serieName) {
    final Matcher m = serieNamePattern.matcher(serieName);
    return m.matches();
  }

}

