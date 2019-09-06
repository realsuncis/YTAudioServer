public class Job
{
    OPCODE opcode;
    Object[] data;

    Job(OPCODE opcode, Object[] data)
    {
        this.opcode = opcode;
        this.data = data;
    }
}

enum OPCODE {START_STREAM, SEND_AUDIO_DURATION
};