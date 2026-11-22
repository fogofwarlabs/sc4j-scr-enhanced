package org.fogofwarlabs.sc4j.screnhanced.script.sync;

import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayModCommand;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Tests the mod commands of script command values ({@link ScriptCommands}): the split into commands with their header, the join per player,
 * and the commands that are no script commands or do not continue a value.
 */
final class ScriptCommandsTest {
    private static final int MORE_FLAG = 1;
    private static final int CONTINUATION_FLAG = 2;

    private static byte @NotNull [] createValue(int length) {
        byte[] value = new byte[length];

        for (int index = 0; index < length; index++) {
            value[index] = (byte) index;
        }

        return value;
    }

    @Test
    void split_should_fill_commands_up_to_the_data_limit_with_a_header_each() {
        byte[] value = createValue(ScriptCommands.MAXIMUM_PART_LENGTH * 2 + 1);
        List<byte[]> commands = ScriptCommands.split(value);

        Assertions.assertEquals(3, commands.size());
        Assertions.assertEquals(ReplayModCommand.MAXIMUM_DATA_LENGTH, commands.getFirst().length);
        Assertions.assertEquals(ScriptCommands.HEADER_LENGTH + 1, commands.getLast().length);
        Assertions.assertArrayEquals(new byte[]{(byte) ScriptCommands.TAG, MORE_FLAG},
                                     Arrays.copyOf(commands.getFirst(), ScriptCommands.HEADER_LENGTH));
        Assertions.assertEquals(MORE_FLAG | CONTINUATION_FLAG, commands.get(1)[ScriptCommands.HEADER_LENGTH - 1]);
        Assertions.assertEquals(CONTINUATION_FLAG, commands.getLast()[ScriptCommands.HEADER_LENGTH - 1], "the last command has no more flag");
        Assertions.assertThrows(IllegalArgumentException.class, () -> ScriptCommands.split(new byte[0]));
    }

    @Test
    void read_should_join_the_commands_of_each_player_into_values() {
        byte[] large = createValue(ScriptCommands.MAXIMUM_PART_LENGTH + 10);
        List<byte[]> first = ScriptCommands.split(large);
        List<byte[]> second = ScriptCommands.split(new byte[]{7});
        ScriptCommands commands = new ScriptCommands();

        Assertions.assertNull(commands.read(0, first.getFirst()));

        ScriptCommandMessage smallMessage = commands.read(3, second.getFirst());
        ScriptCommandMessage largeMessage = commands.read(0, first.getLast());

        Assertions.assertNotNull(smallMessage);
        Assertions.assertEquals(3, smallMessage.getPlayer());
        Assertions.assertArrayEquals(new byte[]{7}, smallMessage.getValue());
        Assertions.assertNotNull(largeMessage);
        Assertions.assertEquals(0, largeMessage.getPlayer());
        Assertions.assertArrayEquals(large, largeMessage.getValue());
    }

    @Test
    void read_should_skip_other_commands_and_drop_a_value_that_is_not_continued() {
        byte[] large = createValue(ScriptCommands.MAXIMUM_PART_LENGTH + 10);
        List<byte[]> broken = ScriptCommands.split(large);
        byte[] other = ScriptCommands.split(new byte[]{9}).getFirst();
        ScriptCommands commands = new ScriptCommands();

        Assertions.assertNull(commands.read(0, new byte[]{(byte) 0xE1, 0x51, 1}), "another mod's command");
        Assertions.assertNull(commands.read(0, new byte[]{(byte) ScriptCommands.TAG, 4, 0}), "unknown flags");
        Assertions.assertNull(commands.read(-1, other), "a player outside the slots");
        Assertions.assertNull(commands.read(0, broken.getLast()), "a continuation without an open value");
        Assertions.assertNull(commands.read(0, broken.getFirst()));

        ScriptCommandMessage message = commands.read(0, other);

        Assertions.assertNotNull(message, "a first command drops the open value");
        Assertions.assertArrayEquals(new byte[]{9}, message.getValue());
        Assertions.assertNull(commands.read(0, broken.getLast()), "the rest of a dropped value is dropped");
    }
}
