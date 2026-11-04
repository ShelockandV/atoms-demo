package dev.atoms;

import java.util.List;
import java.util.Map;

/** One immutable snapshot shared by planning, generation and repair attempts. */
record ProjectContext(String title, String baseVersion, List<Map<String,String>> history, Map<String,String> files) {
 ProjectContext {
  history=history.stream().map(Map::copyOf).toList();
  files=Map.copyOf(files);
 }
}
