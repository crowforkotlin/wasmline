(component
  (type $host-api
    (instance
      (type $host-greet-type (func (param "value" string) (result string)))
      (export "greet" (func (type $host-greet-type)))
    )
  )
  (import "example:host/api" (instance $host (type $host-api)))
  (alias export $host "greet" (func $greet))

  (core module $main
    (type $host-greet-core-type (func (param i32 i32 i32)))
    (type $component-run-core-type (func (param i32 i32) (result i32)))
    (import "host" "greet" (func $greet (type $host-greet-core-type)))
    (memory (export "memory") 1)
    (global $heap (mut i32) (i32.const 1024))
    (func $cabi_realloc (export "cabi_realloc")
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
    ;; Canonical lower appends the result-area pointer after flattened parameters.
    (func $run (type $component-run-core-type) (param $value-ptr i32) (param $value-len i32) (result i32)
      local.get $value-ptr
      local.get $value-len
      i32.const 2048
      call $greet
      i32.const 2048
    )
    (export "run" (func $run))
  )
  (core module $wit-component-shim-module
    (type $indirect-host-greet-type (func (param i32 i32 i32)))
    (table $imports 1 1 funcref)
    (export "0" (func $indirect-host-greet))
    (export "$imports" (table $imports))
    (func $indirect-host-greet (type $indirect-host-greet-type)
      (param $result-ptr i32)
      (param $value-ptr i32)
      (param $value-len i32)
      local.get $result-ptr
      local.get $value-ptr
      local.get $value-len
      i32.const 0
      call_indirect (type $indirect-host-greet-type)
    )
  )
  (core module $wit-component-fixup
    (type $indirect-host-greet-type (func (param i32 i32 i32)))
    (import "" "0" (func (type $indirect-host-greet-type)))
    (import "" "$imports" (table $imports 1 1 funcref))
    (elem (i32.const 0) func 0)
  )
  (core instance $wit-component-shim-instance
    (instantiate $wit-component-shim-module)
  )
  (alias core export $wit-component-shim-instance "0" (core func $indirect-host-greet))
  (alias core export $wit-component-shim-instance "$imports" (core table $imports))
  (core instance $host-core
    (export "greet" (func $indirect-host-greet))
  )
  (core instance $main-instance (instantiate $main
    (with "host" (instance $host-core))
  ))
  (alias core export $main-instance "memory" (core memory $memory))
  (alias core export $main-instance "cabi_realloc" (core func $realloc))
  (core func $greet-lowered
    (canon lower
      (func $greet)
      (memory $memory)
      (realloc $realloc)
      string-encoding=utf8
    )
  )
  (core instance $fixup-args
    (export "$imports" (table $imports))
    (export "0" (func $greet-lowered))
  )
  (core instance $fixup (instantiate $wit-component-fixup
    (with "" (instance $fixup-args))
  ))
  (alias core export $main-instance "run" (core func $run-core))
  (type $component-run-type (func (param "value" string) (result string)))
  (func $run (type $component-run-type)
    (canon lift
      (core func $run-core)
      (memory $memory)
      (realloc $realloc)
      string-encoding=utf8
    )
  )
  (export "run" (func $run))
)
