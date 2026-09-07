(module
  (type $host-add (func (param i32 i32) (result i32)))
  (import "env" "host_add" (func $host-add (type $host-add)))

  (memory (export "memory") 1)

  (func (export "add") (param i32 i32) (result i32)
    local.get 0
    local.get 1
    i32.add
  )

  (func (export "add64") (param i64 i64) (result i64)
    local.get 0
    local.get 1
    i64.add
  )

  (func (export "neg_f32") (param f32) (result f32)
    local.get 0
    f32.neg
  )

  (func (export "neg_f64") (param f64) (result f64)
    local.get 0
    f64.neg
  )

  (func (export "pair") (param i32) (result i32 i64)
    local.get 0
    i64.const 42
  )

  (func (export "void") (param i32)
    local.get 0
    drop
  )

  (func (export "call_host") (param i32 i32) (result i32)
    local.get 0
    local.get 1
    call $host-add
  )

  (func (export "trap")
    unreachable
  )
)
