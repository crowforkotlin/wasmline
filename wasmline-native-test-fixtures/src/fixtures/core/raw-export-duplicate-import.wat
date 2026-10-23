(module
  (type $host-add (func (param i32 i32) (result i32)))
  (import "env" "host_add" (func $host-add-first (type $host-add)))
  (import "env" "host_add" (func $host-add-second (type $host-add)))

  (func (export "add") (param i32 i32) (result i32)
    local.get 0
    local.get 1
    i32.add
  )

  (func (export "call_host_first") (param i32 i32) (result i32)
    local.get 0
    local.get 1
    call $host-add-first
  )

  (func (export "call_host_second") (param i32 i32) (result i32)
    local.get 0
    local.get 1
    call $host-add-second
  )
)
