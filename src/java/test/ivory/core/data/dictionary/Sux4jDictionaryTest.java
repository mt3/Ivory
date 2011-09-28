package ivory.core.data.dictionary;

import it.unimi.dsi.bits.TransformationStrategies;
import it.unimi.dsi.fastutil.io.BinIO;
import it.unimi.dsi.sux4j.mph.MinimalPerfectHashFunction;
import it.unimi.dsi.sux4j.mph.TwoStepsLcpMonotoneMinimalPerfectHashFunction;
import it.unimi.dsi.util.FrontCodedStringList;

import java.io.File;
import java.io.IOException;
import java.util.List;

import junit.framework.TestCase;

import org.junit.Test;

import com.google.common.collect.Lists;

public class Sux4jDictionaryTest extends TestCase {

  @Test
  public void test1() throws IOException {
    List<String> fruits = Lists.newArrayList("apple", "bananna", "cherry", "grape", "watermelon");

    TwoStepsLcpMonotoneMinimalPerfectHashFunction<CharSequence> dictionary =
        new TwoStepsLcpMonotoneMinimalPerfectHashFunction<CharSequence>(fruits,
            TransformationStrategies.prefixFreeIso());

    System.out.println(dictionary.getLong("apple"));
    System.out.println(dictionary.getLong("bananna"));
    System.out.println(dictionary.getLong("cherry"));
    System.out.println(dictionary.getLong("grape"));
    System.out.println(dictionary.getLong("watermelon"));

    List<String> fruits2 = Lists.newArrayList("watermelon", "bananna", "cherry", "apple", "grape");

    MinimalPerfectHashFunction<CharSequence> dictionary2 =
        new MinimalPerfectHashFunction<CharSequence>(fruits2,
            TransformationStrategies.prefixFreeIso());

    System.out.println(dictionary2.getLong("watermelon"));
    System.out.println(dictionary2.getLong("bananna"));
    System.out.println(dictionary2.getLong("cherry"));
    System.out.println(dictionary2.getLong("apple"));
    System.out.println(dictionary2.getLong("grape"));
  }

  @Test
  public void test2() throws IOException {
    List<String> terms = Lists.newArrayList("apple", "bannana", "cherry", "grape", "watermelon");
    FrontCodedStringList list = new FrontCodedStringList(terms, 8, true);

    assertEquals("apple", list.get(0).toString());
    assertEquals("bannana", list.get(1).toString());
    assertEquals("cherry", list.get(2).toString());
    assertEquals("grape", list.get(3).toString());
    assertEquals("watermelon", list.get(4).toString());

    File f = new File("test.dat");
    BinIO.storeObject(list, f);
    try {
      FrontCodedStringList list2 = (FrontCodedStringList) BinIO.loadObject(f);

      assertEquals("apple", list2.get(0).toString());
      assertEquals("bannana", list2.get(1).toString());
      assertEquals("cherry", list2.get(2).toString());
      assertEquals("grape", list2.get(3).toString());
      assertEquals("watermelon", list2.get(4).toString());
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
    }

    f.delete();
  }
}
