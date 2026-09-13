import java.lang.reflect.*;
public class SplitProbe {
  public static void main(String[] a) throws Exception {
    Class<?> c = Class.forName("com.linguareader.shared.tts.SentenceSplitter");
    Field inst = c.getField("INSTANCE");
    Object obj = inst.get(null);
    Method split = c.getMethod("split", String.class, int.class);
    String[] cases = {
      "He said her's was gone.",
      "her's",
      "He said her's was gone. The next morning it rained.",
      "Tom's dog ran. her's barked.",
      "I like her's. it is red",
      "James's Park is nice. Gen. Ross joined.",
      "It is her's 'book' here."
    };
    for (String s : cases) {
      Object r = split.invoke(obj, s, Integer.MAX_VALUE);
      java.util.List<?> list = (java.util.List<?>) r;
      System.out.println("IN : " + s);
      System.out.println("OUT(" + list.size() + "): " + list);
      System.out.println();
    }
  }
}
