package dev.toka.worlds;

import org.junit.Test;
import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.util.Map;
import static org.junit.Assert.*;

public class ButtonLangTest {
    private static final String[] KEYS = {"ui.common.back","ui.common.prev","ui.common.next","ui.common.close","ui.common.cancel","ui.menu.search","ui.menu.help","ui.plan.check","ui.plan.clear","ui.plan.cancel"};

    private static Object get(Object node, String key) {
        for (String part : key.split(java.util.regex.Pattern.quote("."))) node = ((Map<?, ?>) node).get(part);
        return node;
    }

    @Test public void buttonTextsHaveNoColourExceptLeadingRed() throws Exception {
        for (String file : new String[]{"zh-TW.yml"}) {
            try (InputStream in = ButtonLangTest.class.getResourceAsStream("/lang/" + file)) {
                Object root = new Yaml().load(in);
                for (String key : KEYS) {
                    String value = String.valueOf(get(root, key));
                    String rest = value.startsWith("§c") ? value.substring(2) : value;
                    assertFalse(file + " " + key, rest.contains("§"));
                }
            }
        }
    }
}
