import java.lang.reflect.*;
import java.util.*;
public class BatchProbe {
  public static void main(String[] a) throws Exception {
    Class<?> c = Class.forName("com.linguareader.shared.ai.AiBookTranslator");
    Object obj = c.getField("INSTANCE").get(null);
    Method m = c.getMethod("groupIntoBatches", int.class, List.class, int.class);

    StringBuilder huge = new StringBuilder();
    for (int i = 0; i < 5000; i++) huge.append("This is a very long paragraph sentence number ").append(i).append(". ");
    List<String> paras = new ArrayList<>();
    paras.add("A normal short paragraph.");
    paras.add(huge.toString());                 // 一个 4.9 万字符的超长段
    paras.add("Another normal paragraph.");

    Object res = m.invoke(obj, 0, paras, 6000);
    List<?> batches = (List<?>) res;
    System.out.println("段落数=" + paras.size() + " 超长段长度=" + huge.length() + " 批次上限=6000");
    System.out.println("产出批次数=" + batches.size());
    for (Object b : batches) {
      Method cc = b.getClass().getMethod("getCharCount");
      Method pi = b.getClass().getMethod("getParagraphIndices");
      System.out.println("  批次 charCount=" + cc.invoke(b) + "  段落下标=" + pi.invoke(b));
    }
  }
}
