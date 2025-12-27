package com.github.kraudy.compiler;

import java.util.ArrayList;
import java.util.List;

/*
 *  Simple POJO for param's value encapsulation and history tracking.
 */
public class ParamValue {
    private String current = null;
    private Integer count = 0;
    private final List<String> history = new ArrayList<String>();

    public ParamValue() {
    }

    // Constructor for initial insertion
    public ParamValue(String value) {
      this.current = value;
      this.history.add("[INIT]");
      this.history.add(value);
      this.count += 1; //TODO: Should this be +=2?
    }

    // Getters
    public String get() {
      return current; // if null, returns null, dah.
    }

    public String put(String value) {
      this.history.add(value);
      this.current = value;
      this.count += 1;
      return getPrevious();
    }

    public String getPrevious() {
      if (count <= 1) return null;
      return this.history.get(count - 1);
    }

    public String getFirst() {
      return this.history.get(1);
    }

    public String remove() {
      this.history.add("[REMOVED]");
      this.count += 1; // Adds to the history
      this.current = null;
      return getPrevious();
    }

    public List<String> getHistory() {
      return new ArrayList<String>(history);  // Return copy to prevent external mutation
    }

    public Integer getCount() {
      return this.count;
    }

    public String getLastChange() {
      return history.isEmpty() ? null : history.get(history.size() - 1);
    }

    public boolean wasRemoved() {
      return "[REMOVED]".equals(getLastChange());
    }

    public boolean wasInit() {
      return "[INIT]".equals(history.get(0));
    }
}