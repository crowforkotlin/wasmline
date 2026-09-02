(component
  (type $choice-type
    (variant
      (case "number" s32)
      (case "none")
    )
  )
  (type $shade-type (enum "red" "blue"))
  (export $choice "choice" (type $choice-type))
  (export $shade "shade" (type $shade-type))
  (type $host-api
    (instance
      (type $host-choice-type
        (variant
          (case "number" s32)
          (case "none")
        )
      )
      (type $host-shade-type (enum "red" "blue"))
      (export "choice" (type $host-choice (eq $host-choice-type)))
      (export "shade" (type $host-shade (eq $host-shade-type)))
      (type $inspect-type
        (func
          (param "choice" $host-choice)
          (param "shade" $host-shade)
          (result s32)
        )
      )
      (export "inspect" (func (type $inspect-type)))
    )
  )
  (import "example:host/variant-enum" (instance $host (type $host-api)))
  (alias export $host "inspect" (func $inspect))

  (core module $main
    (type $host-inspect-core-type
      (func
        (param i32 i32 i32)
        (result i32)
      )
    )
    (type $component-run-core-type
      (func
        (param i32 i32 i32)
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
      (param $choice-tag i32)
      (param $choice-value i32)
      (param $shade i32)
      (result i32)
      local.get $choice-tag
      local.get $choice-value
      local.get $shade
      call $inspect
    )
    (export "run" (func $run))
  )
  (core module $wit-component-shim-module
    (type $indirect-host-inspect-type
      (func
        (param i32 i32 i32)
        (result i32)
      )
    )
    (table $imports 1 1 funcref)
    (export "0" (func $indirect-host-inspect))
    (export "$imports" (table $imports))
    (func $indirect-host-inspect (type $indirect-host-inspect-type)
      (param $choice-tag i32)
      (param $choice-value i32)
      (param $shade i32)
      (result i32)
      local.get $choice-tag
      local.get $choice-value
      local.get $shade
      i32.const 0
      call_indirect (type $indirect-host-inspect-type)
    )
  )
  (core module $wit-component-fixup
    (type $indirect-host-inspect-type
      (func
        (param i32 i32 i32)
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
      (param "choice" $choice)
      (param "shade" $shade)
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
