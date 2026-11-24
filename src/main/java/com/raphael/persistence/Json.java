package com.raphael.persistence;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * 최소 JSON 직렬화기 (외부 의존성 없음). Map (삽입 순서 유지), Collection, String, Number,
 * Boolean, null, Optional* 를 지원한다. 숫자는 로케일과 무관하게 쓴다: 정수는 그대로, 실수는
 * {@link BigDecimal#toPlainString()}. NaN·무한대·빈 Optional 은 {@code null}.
 */
final class Json {

  private Json() {
  }

  static String write(Object value) {
    StringBuilder out = new StringBuilder();
    write(out, value, 0);
    out.append('\n');
    return out.toString();
  }

  private static void write(StringBuilder out, Object value, int indent) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof Optional<?> optional) {
      write(out, optional.orElse(null), indent);
    } else if (value instanceof OptionalLong optional) {
      write(out, optional.isPresent() ? optional.getAsLong() : null, indent);
    } else if (value instanceof OptionalDouble optional) {
      write(out, optional.isPresent() ? optional.getAsDouble() : null, indent);
    } else if (value instanceof String s) {
      string(out, s);
    } else if (value instanceof Double || value instanceof Float) {
      double d = ((Number) value).doubleValue();
      out.append(Double.isFinite(d) ? BigDecimal.valueOf(d).toPlainString() : "null");
    } else if (value instanceof Number || value instanceof Boolean) {
      out.append(value);
    } else if (value instanceof Map<?, ?> map) {
      if (map.isEmpty()) {
        out.append("{}");
        return;
      }
      out.append("{\n");
      int i = 0;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        pad(out, indent + 1);
        string(out, String.valueOf(entry.getKey()));
        out.append(": ");
        write(out, entry.getValue(), indent + 1);
        out.append(++i < map.size() ? ",\n" : "\n");
      }
      pad(out, indent);
      out.append('}');
    } else if (value instanceof Collection<?> list) {
      if (list.isEmpty()) {
        out.append("[]");
        return;
      }
      out.append("[\n");
      int i = 0;
      for (Object item : list) {
        pad(out, indent + 1);
        write(out, item, indent + 1);
        out.append(++i < list.size() ? ",\n" : "\n");
      }
      pad(out, indent);
      out.append(']');
    } else {
      string(out, value.toString());
    }
  }

  private static void string(StringBuilder out, String s) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }

  private static void pad(StringBuilder out, int indent) {
    out.append("  ".repeat(indent));
  }
}
