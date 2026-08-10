(module
  (type $host-invoke (func (param i32 i32 i32 i32 i32 i32 i32)))
  (import "host" "invoke" (func $host-invoke (type $host-invoke)))

  (memory (export "memory") 1)
  (global $heap (mut i32) (i32.const 1024))

  (func (export "cabi_realloc")
    (param $old-ptr i32)
    (param $old-size i32)
    (param $align i32)
    (param $new-size i32)
    (result i32)
    (local $result i32)

    global.get $heap
    local.set $result
    global.get $heap
    local.get $new-size
    i32.add
    global.set $heap
    local.get $result
  )

  (func (export "plugin#invoke")
    (param $action-ptr i32)
    (param $action-len i32)
    (param $codec-ptr i32)
    (param $codec-len i32)
    (param $payload-ptr i32)
    (param $payload-len i32)
    (result i32)

    i32.const 64
    i32.const 0
    i32.store8
    i32.const 68
    local.get $payload-ptr
    i32.store
    i32.const 72
    local.get $payload-len
    i32.store
    i32.const 64
  )

  (func (export "cabi_post_plugin#invoke") (param $result-ptr i32))
)
