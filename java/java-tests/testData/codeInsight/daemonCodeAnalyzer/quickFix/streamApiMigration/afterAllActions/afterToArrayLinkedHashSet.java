// "Replace with toArray" "true-preview"

import java.util.*;

public class Main {
  public CharSequence[] testToArray(List<String> data) {
      return data.stream().filter(str -> !str.isEmpty()).distinct().toArray(CharSequence[]::new);
  }
}