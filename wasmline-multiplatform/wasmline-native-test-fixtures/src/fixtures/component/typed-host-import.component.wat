(component
  (type $host-api
    (instance
      (type $increment-type (func (param "value" s32) (result s32)))
      (export "increment" (func (type $increment-type)))
    )
  )
  (import "example:host/api" (instance $host (type $host-api)))
  (alias export $host "increment" (func $increment))
  (core func $increment-lowered (canon lower (func $increment)))

  (core module $main
    (type $run-type (func (param i32) (result i32)))
    (import "host" "increment" (func $increment (type $run-type)))
    (func $run (type $run-type) (param $value i32) (result i32)
      local.get $value
      call $increment
    )
    (export "run" (func $run))
  )
  (core instance $host-core
    (export "increment" (func $increment-lowered))
  )
  (core instance $main-instance
    (instantiate $main
      (with "host" (instance $host-core))
    )
  )
  (type $run-type (func (param "value" s32) (result s32)))
  (alias core export $main-instance "run" (core func $run-core))
  (func $run (type $run-type) (canon lift (core func $run-core)))
  (export "run" (func $run))
)
