import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.ValueWrapper;

public class RunBoxDump {
  public static void main(String[] args) throws Exception {
    try {
      Class<?> c = Class.forName("crow.wasmline.kotlin.runners.JvmBoxTestGenerated");
      Object o = c.getDeclaredConstructor().newInstance();
      Class<?> infoClass = Class.forName("org.jetbrains.kotlin.test.services.KotlinTestInfo");
      Object testInfo = infoClass.getConstructors()[0].newInstance(
          c.getName(),
          "testEchoProxyRoundTrip",
          java.util.Collections.emptySet()
      );
      c.getMethod("initTestInfo", infoClass).invoke(o, testInfo);
      c.getMethod("testEchoProxyRoundTrip").invoke(o);
      System.out.println("TEST_PASSED");
    } catch (Throwable t) {
      dump(unwrap(t), new HashSet<>());
      throw t;
    }
  }

  private static Throwable unwrap(Throwable t) {
    while (t instanceof InvocationTargetException ite && ite.getTargetException() != null) {
      t = ite.getTargetException();
    }
    return t;
  }

  private static void dump(Throwable t, Set<Throwable> seen) {
    if (t == null || !seen.add(t)) return;
    System.out.println("THROWABLE=" + t.getClass().getName() + ": " + t.getMessage());
    if (t instanceof AssertionFailedError afe) {
      print("EXPECTED", afe.getExpected());
      print("ACTUAL", afe.getActual());
    }
    for (Throwable s : t.getSuppressed()) dump(unwrap(s), seen);
    dump(unwrap(t.getCause()), seen);
  }

  private static void print(String label, ValueWrapper wrapper) {
    System.out.println(label + "_BEGIN");
    System.out.println(valueOf(wrapper));
    System.out.println(label + "_END");
  }

  private static Object valueOf(ValueWrapper wrapper) {
    if (wrapper == null) return null;
    try {
      if (wrapper.getClass().getName().equals("org.opentest4j.FileInfo")) {
        Method getPath = wrapper.getClass().getMethod("getPath");
        Method getContents = wrapper.getClass().getMethod("getContents");
        return "FILEINFO:" + getPath.invoke(wrapper) + "\n" + new String((byte[]) getContents.invoke(wrapper));
      }
    } catch (Throwable ignored) {
    }
    try {
      Method method = wrapper.getClass().getMethod("getValue");
      return method.invoke(wrapper);
    } catch (Throwable ignored) {
      return String.valueOf(wrapper);
    }
  }
}

