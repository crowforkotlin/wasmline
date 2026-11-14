(module
  (import "env" "host" (func $host))
  (func $start call $host)
  (start $start)
  (func (export "invoke") call $host)
  (func (export "add") (param i32 i32) (result i32)
    local.get 0
    local.get 1
    i32.add)
)
