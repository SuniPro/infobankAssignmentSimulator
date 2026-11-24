package com.raphael.report;

import java.util.ArrayList;
import java.util.List;

/** 열 너비를 맞춘 고정폭 텍스트 표. 첫 열은 왼쪽, 나머지는 오른쪽 정렬. */
final class TextTable {

  private final List<String> header;
  private final List<List<String>> rows = new ArrayList<>();

  TextTable(List<String> header) {
    this.header = List.copyOf(header);
  }

  TextTable row(List<String> cells) {
    if (cells.size() != header.size()) {
      throw new IllegalArgumentException("Row width " + cells.size() + " != " + header.size());
    }
    rows.add(List.copyOf(cells));
    return this;
  }

  boolean isEmpty() {
    return rows.isEmpty();
  }

  String render(String indent) {
    int[] widths = new int[header.size()];
    List<List<String>> all = new ArrayList<>();
    all.add(header);
    all.addAll(rows);
    for (List<String> r : all) {
      for (int c = 0; c < r.size(); c++) {
        widths[c] = Math.max(widths[c], r.get(c).length());
      }
    }
    StringBuilder out = new StringBuilder();
    for (List<String> r : all) {
      out.append(indent);
      for (int c = 0; c < r.size(); c++) {
        String cell = r.get(c);
        String pad = " ".repeat(widths[c] - cell.length());
        out.append(c == 0 ? cell + pad : "  " + pad + cell);
      }
      out.append('\n');
    }
    return out.toString();
  }
}
