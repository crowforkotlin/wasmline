#include "myclib.h"
#include <stdio.h>
#include <string.h>

int native_add(int a, int b) {
    return a + b;
}

void native_greet(const char* name, char* buffer, int buffer_size) {
    snprintf(buffer, buffer_size, "Hello C, %s!", name);
}

MyPoint make_point(int x, int y) {
    MyPoint p = {x, y};
    return p;
}