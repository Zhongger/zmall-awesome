package com.xunqi.gulimall.gateway;

import java.util.Scanner;

public class FIndWaySolution {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("请输入面额数：");
        int n = scanner.nextInt();
        int[] arr = new int[n];
        System.out.println("请输入面额集合：");

        for (int i = 0; i < arr.length; i++) {
            arr[i] = scanner.nextInt();

        }
        System.out.println("请输入要找的钱：");
        int money = scanner.nextInt();
        System.out.println("结果为：");
        getSolution(arr, money);
    }

    public static void getSolution(int[] value, int money) {
        sort(value);
        int[] count = new int[value.length];
        for (int i = 0; i < value.length; i++) {
            while (money >= value[i]) {
                money -= value[i];
                count[i]++;
            }

            if (count[i] != 0) {
                System.out.println(count[i] + "张" + value[i] + "元");
            }
        }


    }

    public static void sort(int[] value) {
        for (int i = 0; i < value.length; i++) {
            for (int j = 0; j < value.length; j++) {
                if (value[j] < value[i]) {
                    int temp = value[i];
                    value[i] = value[j];
                    value[j] = temp;
                }
            }
        }
    }

}
