package net.coderbot.iris.shaderpack.preprocessor;

import net.coderbot.iris.shaderpack.StringPair;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JcppProcessorTest {
    @Test
    void removesNulCharactersBeforePreprocessing() {
        String result = JcppProcessor.glslPreprocessSource("void main() {\u0000}\n", List.<StringPair>of());

        assertEquals("void main() {}\n\n", result);
    }
}
