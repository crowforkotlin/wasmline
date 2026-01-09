#ifndef MYCLIB_H
#define MYCLIB_H

// 一个简单的计算函数
int native_add(int a, int b);

// 一个需要传指针的函数
void native_greet(const char* name, char* buffer, int buffer_size);

// 一个 C 结构体
typedef struct {
    int x;
    int y;
} MyPoint;

MyPoint make_point(int x, int y);

#endif