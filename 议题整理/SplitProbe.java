import java.lang.reflect.*;
public class SplitProbe {
  public static void main(String[] a) throws Exception {
    Class<?> c = Class.forName("com.linguareader.shared.tts.SentenceSplitter");
    Object obj = c.getField("INSTANCE").get(null);
    Method split = c.getMethod("split", String.class, int.class);
    String longText = "The quick brown fox jumps over the lazy dog and keeps running until it reaches the river bank where her's was waiting for the ferry to arrive at the dock before the sun goes down and the lights come on in the village across the water and everyone gathers to hear the news of the day.";
    String[] cases = {
      "It was her\u2019s.",                       // 排版撇号 U+2019
      "her\u2019s book",
      "He said her\u2019s was gone. Then it rained.",
      "her's\tbook",
      "her's\nbook",
      "a her's b her's c",
      longText
    };
    for (String s : cases) {
      Object r = split.invoke(obj, s, 300);     // TTS 线：带 300 上限
      java.util.List<?> list = (java.util.List<?>) r;
      System.out.println("IN : " + (s.length()>60 ? s.substring(0,60)+"…(" + s.length() + " chars)" : s));
      System.out.println("OUT(" + list.size() + "): " + list);
      System.out.println();
    }
  }
}
