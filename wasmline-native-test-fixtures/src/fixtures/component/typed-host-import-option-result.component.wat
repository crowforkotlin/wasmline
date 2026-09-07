(component
  (type $maybe-type (option s32))
  (type $outcome-type (result s32 (error s32)))
  (export $maybe "maybe" (type $maybe-type))
  (export $outcome "outcome" (type $outcome-type))
  (type $host-api
    (instance
      (type $host-maybe-type (option s32))
      (type $host-outcome-type (result s32 (error s32)))
      (export "maybe" (type $host-maybe (eq $host-maybe-type)))
      (export "outcome" (type $host-outcome (eq $host-outcome-type)))
      (type $inspect-type
        (func
          (param "maybe" $host-maybe)
          (result $host-outcome)
        )
      )
      (export "inspect" (func (type $inspect-type)))
    )
  )
  (import "example:host/option-result" (instance $host (type $host-api)))
  (alias export $host "inspect" (func $inspect))

  (core module $main
    (type $host-inspect-core-type
      (func
        (param i32 i32 i32)
      )
    )
    (type $component-run-core-type
      (func
        (param i32 i32)
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
      (param $maybe-tag i32)
      (param $maybe-value i32)
      (result i32)
      local.get $maybe-tag
      local.get $maybe-value
      i32.const 2048
      call $inspect
      i32.const 2048
    )
    (export "run" (func $run))
  )
  (core module $wit-component-shim-module
    (type $indirect-host-inspect-type
      (func
        (param i32 i32 i32)
      )
    )
    (table $imports 1 1 funcref)
    (export "0" (func $indirect-host-inspect))
    (export "$imports" (table $imports))
    (func $indirect-host-inspect (type $indirect-host-inspect-type)
      (param $maybe-tag i32)
      (param $maybe-value i32)
      (param $result-ptr i32)
      local.get $maybe-tag
      local.get $maybe-value
      local.get $result-ptr
      i32.const 0
      call_indirect (type $indirect-host-inspect-type)
    )
  )
  (core module $wit-component-fixup
    (type $indirect-host-inspect-type
      (func
        (param i32 i32 i32)
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
      (param "maybe" $maybe-type)
      (result $outcome-type)
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
