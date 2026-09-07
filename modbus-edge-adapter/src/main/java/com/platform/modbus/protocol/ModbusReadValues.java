package com.platform.modbus.protocol;

public record ModbusReadValues(boolean[] bits, int[] registers) {

    public ModbusReadValues {
        bits = bits == null ? null : bits.clone();
        registers = registers == null ? null : registers.clone();
    }

    @Override
    public boolean[] bits() {
        return bits == null ? null : bits.clone();
    }

    @Override
    public int[] registers() {
        return registers == null ? null : registers.clone();
    }
}
