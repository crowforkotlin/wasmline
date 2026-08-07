(component
  (type $bytes-type (list u8))
  (type $pair-type (tuple u32 s32))
  (type $stats-type
    (record
      (field "count" s32)
      (field "enabled" bool)
    )
  )
  (export $bytes "bytes" (type $bytes-type))
  (export $pair "pair" (type $pair-type))
  (export $stats "stats" (type $stats-type))
  (type $host-api
    (instance
      (type $host-bytes-type (list u8))
      (type $host-pair-type (tuple u32 s32))
      (type $host-stats-type
        (record
          (field "count" s32)
          (field "enabled" bool)
        )
      )
      (export "bytes" (type $host-bytes (eq $host-bytes-type)))
      (export "pair" (type $host-pair (eq $host-pair-type)))
      (export "stats" (type $host-stats (eq $host-stats-type)))
      (type $inspect-type
        (func
          (param "bytes" $host-bytes)
          (param "pair" $host-pair)
          (param "stats" $host-stats)
          (result s32)
        )
      )
      (export "inspect" (func (type $inspect-type)))
    )
  )
  (import "example:host/shapes" (instance $host (type $host-api)))
  (alias export $host "inspect" (func $inspect))

  (core module $main
    (type $host-inspect-core-type
      (func
        (param i32 i32 i32 i32 i32 i32)
        (result i32)
      )
    )
    (type $component-run-core-type
      (func
        (param i32 i32 i32 i32 i32 i32)
        (result i32)
      )
    )
    (type $cabi-realloc-type (func (param i32 i32 i32 i32) (result i32)))
    (import "host" "inspect" (func $inspect (type $host-inspect-core-type)))
    (memory (export "memory") 1)
    (global $heap (mut i32) (i32.const 1024))
    (func $cabi_realloc (export "cabi_realloc") (type $cabi-realloc-type)
      (param $old-ptr i32)
      (param $old-size i32)
      (param $align i32)
      (param $new-size i32)
      (result i32)
      (local $result i32)
      (local $mask i32)
      local.get $align
      i32.const 1
      i32.sub
      local.set $mask
      global.get $heap
      local.get $mask
      i32.add
      local.get $mask
      i32.const -1
      i32.xor
      i32.and
      local.set $result
      local.get $result
      local.get $new-size
      i32.add
      global.set $heap
      local.get $result
    )
    (func $run (type $component-run-core-type)
      (param $bytes-ptr i32)
      (param $bytes-len i32)
      (param $pair-first i32)
      (param $pair-second i32)
      (param $stats-count i32)
      (param $stats-enabled i32)
      (result i32)
      local.get $bytes-ptr
      local.get $bytes-len
      local.get $pair-first
      local.get $pair-second
      local.get $stats-count
      local.get $stats-enabled
      call $inspect
    )
    (export "run" (func $run))
  )
  (core module $wit-component-shim-module
    (type $indirect-host-inspect-type
      (func
        (param i32 i32 i32 i32 i32 i32)
        (result i32)
      )
    )
    (table $imports 1 1 funcref)
    (export "0" (func $indirect-host-inspect))
    (export "$imports" (table $imports))
    (func $indirect-host-inspect (type $indirect-host-inspect-type)
      (param $bytes-ptr i32)
      (param $bytes-len i32)
      (param $pair-first i32)
      (param $pair-second i32)
      (param $stats-count i32)
      (param $stats-enabled i32)
      (result i32)
      local.get $bytes-ptr
      local.get $bytes-len
      local.get $pair-first
      local.get $pair-second
      local.get $stats-count
      local.get $stats-enabled
      i32.const 0
      call_indirect (type $indirect-host-inspect-type)
    )
  )
  (core module $wit-component-fixup
    (type $indirect-host-inspect-type
      (func
        (param i32 i32 i32 i32 i32 i32)
        (result i32)
      )
    )
    (import "" "0" (func (type $indirect-host-inspect-type)))
    (import "" "$imports" (table $imports 1 1 funcref))
    (elem (i32.const 0) func 0)
  )
  (core instance $wit-component-shim-instance
    (instantiate $wit-component-shim-module)
  )
  (alias core export $wit-component-shim-instance "0" (core func $indirect-host-inspect))
  (alias core export $wit-component-shim-instance "$imports" (core table $imports))
  (core instance $host-core
    (export "inspect" (func $indirect-host-inspect))
  )
  (core instance $main-instance (instantiate $main
    (with "host" (instance $host-core))
  ))
  (alias core export $main-instance "memory" (core memory $memory))
  (alias core export $main-instance "cabi_realloc" (core func $realloc))
  (core func $inspect-lowered
    (canon lower
      (func $inspect)
      (memory $memory)
      (realloc $realloc)
    )
  )
  (core instance $fixup-args
    (export "$imports" (table $imports))
    (export "0" (func $inspect-lowered))
  )
  (core instance $fixup (instantiate $wit-component-fixup
    (with "" (instance $fixup-args))
  ))
  (alias core export $main-instance "run" (core func $run-core))
  (type $component-run-type
    (func
      (param "bytes" $bytes)
      (param "pair" $pair)
      (param "stats" $stats)
      (result s32)
    )
  )
  (func $run (type $component-run-type)
    (canon lift
      (core func $run-core)
      (memory $memory)
      (realloc $realloc)
    )
  )
  (export "run" (func $run))
)
