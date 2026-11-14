(module
  (func (export "add") (param i32 i32) (result i32)
    local.get 0
    local.get 1
    i32.add)
  (func (export "\f0\9f\98\80") (result i32) i32.const 42)
  (memory (export "memory-\f0\9f\98\80") 1)
)
