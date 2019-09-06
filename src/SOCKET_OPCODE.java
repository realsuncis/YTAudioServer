public enum SOCKET_OPCODE {
    EXIT(0), AUTH(1), START(2), SEEK(3), SUCCESS(4), FAIL(5);

    private int numVal;

    SOCKET_OPCODE(int numVal) {
        this.numVal = numVal;
    }

    public int getNumVal() {
        return numVal;
    }
}
