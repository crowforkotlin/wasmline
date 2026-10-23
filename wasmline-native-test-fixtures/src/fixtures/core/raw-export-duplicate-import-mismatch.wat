(module
  (type $host-add (func (param i32 i32) (result i32)))
  (type $host-add-wide (func (param i64 i64) (result i64)))
  (import "env" "host_add" (func $host-add (type $host-add)))
  (import "env" "host_add" (func $host-add-wide (type $host-add-wide)))

  (func (export "add") (param i32 i32) (result i32)
    local.get 0
    local.get 1
    call $host-add
  )

  (func (export "add64") (param i64 i64) (result i64)
    local.get 0
    local.get 1
    call $host-add-wide
  )
)
