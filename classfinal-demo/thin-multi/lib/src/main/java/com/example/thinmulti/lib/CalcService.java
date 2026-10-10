package com.example.thinmulti.lib;

/**
 * 业务模块。thin jar 场景下它会被外置到 lib/ 目录，
 * 需要「单独跑一遍 classfinal」才会被加密。
 */
public class CalcService {

    public int add(int a, int b) {
        int sum = a + b;
        return sum * 10;
    }
}
