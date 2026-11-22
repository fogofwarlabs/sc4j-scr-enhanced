package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Checks how the pictures of map scripts are read: a PNG with its alpha, a DXT1 DDS, and the refusal of other bytes.
 */
final class PictureFileUtilsTest {
    @Test
    void read_should_decode_a_png_with_its_alpha() throws IOException {
        byte[] png = ImageFileScenario.createPng();
        Picture picture = PictureFileUtils.read(png, "a.png");

        Assertions.assertTrue(PictureFileUtils.isPicture(png));
        Assertions.assertEquals(2, picture.getWidth());
        Assertions.assertEquals(1, picture.getHeight());
        Assertions.assertEquals(ImageFileScenario.PNG_FIRST_COLOR, picture.getColor().getValue(0));
        Assertions.assertEquals(ImageFileScenario.PNG_SECOND_COLOR, picture.getColor().getValue(1));
    }

    @Test
    void read_should_decode_a_dds() throws IOException {
        Picture picture = PictureFileUtils.read(ImageFileScenario.createDds(), "a.dds");

        Assertions.assertEquals(ImageFileScenario.DDS_SIDE, picture.getWidth());
        Assertions.assertEquals(ImageFileScenario.DDS_SIDE, picture.getHeight());
        Assertions.assertEquals(ImageFileScenario.DDS_COLOR, picture.getColor().getValue(0));
        Assertions.assertEquals(ImageFileScenario.DDS_COLOR, picture.getColor().getValue(ImageFileScenario.DDS_SIDE * ImageFileScenario.DDS_SIDE - 1));
    }

    @Test
    void read_should_refuse_other_bytes() {
        byte[] text = "not a picture".getBytes(StandardCharsets.US_ASCII);
        byte[] brokenPng = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0};

        Assertions.assertFalse(PictureFileUtils.isPicture(text));
        Assertions.assertThrows(IOException.class, () -> PictureFileUtils.read(text, "a.txt"));
        Assertions.assertThrows(IOException.class, () -> PictureFileUtils.read(brokenPng, "b.png"));
    }
}
