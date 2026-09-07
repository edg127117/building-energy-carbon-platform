package com.platform.modbus.protocol;

/** 保存一次只读请求的位值或寄存器快照，并通过防御性复制隔离调用方修改。 */
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
