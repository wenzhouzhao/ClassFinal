package com.example.fatmulti.lib;

/**
 * 业务模块。fat jar 场景下它会被打进主 jar 的 BOOT-INF/lib/，
 * 需要用 -libjars 指定才会被加密。
 */
public class CalcService {

    public int add(int a, int b) {
        int sum = a + b;
        return sum * 10;
    }
}
