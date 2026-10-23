;; Core Wasmline Service bridge probe used by native AOT Core Service tests.
;;
;; The guest exercises the real Core Service ABI through the `env` bridge imports
;; that Session registers, and selects one arm through the first byte of the
;; inbound action string. Every arm publishes its result with
;; bridge_inbound_set_response, so host-visible outcomes stay deterministic.
;;
;; Fixed memory map (memory 1 page = 65536 bytes):
;;   0..64        action copy target (bridge_inbound_copy_params type 0)
;;   128..192     data copy target   (bridge_inbound_copy_params type 1)
;;   4096..4160   response frame (18-byte header plus up to 40 payload bytes)
;;   8192..9216   outbound fast-path scratch result (capacity 1024)
;;   12288..      outbound slow-path result target
;;
;; Arms:
;;   'A' normal invocation; payload = u32(data size) u32(action size) action[0..12]
;;   'B' outbound call with a scratch buffer large enough for the host result
;;   'C' outbound call whose host result exceeds the scratch capacity (slow path)
;;   'D' inbound data copy ending exactly at the end of the linear memory
;;   'E' inbound data copy whose requested length exceeds the available host data
;;   'H' zero-length inbound data copy at the exact memory end
;;   'Z' zero-length response publication (existing missing-response path)
;;   'N','O','P','Q','R','S','T','U','V','W','X','Y' malformed guest ranges,
;;       each followed by an unrelated success publication so a rejection is
;;       observable as a trap instead of a success frame
(module
  (import "env" "bridge_inbound_get_size" (func $get_size (param i32) (result i32)))
  (import "env" "bridge_inbound_copy_params" (func $copy_params (param i32 i32 i32)))
  (import "env" "bridge_inbound_set_response" (func $set_response (param i32 i32)))
  (import "env" "bridge_outbound_call_host"
    (func $call_host (param i32 i32 i32 i32 i32 i32) (result i32)))
  (import "env" "bridge_outbound_get_response" (func $get_response (param i32)))

  (memory (export "memory") 1)

  (func $writeHeader (param $payloadLen i32)
    (i32.store (i32.const 4096) (i32.const 0x464D4C57)) ;; "WLMF" little-endian
    (i32.store8 (i32.const 4100) (i32.const 1))
    (i32.store8 (i32.const 4101) (i32.const 0))
    (i32.store (i32.const 4102) (i32.const 0))
    (i32.store (i32.const 4106) (i32.const 0))
    (i32.store (i32.const 4110) (local.get $payloadLen))
  )

  ;; Publishes a success frame whose payload is the requested byte range.
  (func $echo (param $ptr i32) (param $len i32)
    (call $writeHeader (local.get $len))
    (memory.copy (i32.const 4114) (local.get $ptr) (local.get $len))
    (call $set_response (i32.const 4096) (i32.add (i32.const 18) (local.get $len)))
  )

  (func (export "__wasmline_wasi_entry") (param $actionLen i32) (param $dataLen i32)
    (local $status i32)
    (local $needed i32)

    ;; Both inbound kinds are copied before arm selection so every arm exercises
    ;; both inbound transfer paths with legal ranges.
    (call $copy_params (i32.const 0) (i32.const 0) (i32.const 64))
    (call $copy_params (i32.const 1) (i32.const 128) (i32.const 64))

    (block $done
      ;; 'A' normal invocation; payload = u32(data size) u32(action size) action[0..12].
      (if (i32.eq (i32.load8_u (i32.const 0)) (i32.const 0x41))
        (then
          (call $writeHeader (i32.const 20))
          (i32.store (i32.const 4114) (call $get_size (i32.const 1)))
          (i32.store (i32.const 4118) (call $get_size (i32.const 0)))
          (memory.copy (i32.const 4122) (i32.const 0) (i32.const 12))
          (call $set_response (i32.const 4096) (i32.const 38))
          (br $done)))

      ;; 'B' outbound call with a scratch buffer large enough for the host result.
      (if (i32.eq (i32.load8_u (i32.const 0)) (i32.const 0x42))
        (then
          (local.set $status
            (call $call_host
              (i32.const 0) (local.get $actionLen)
              (i32.const 128) (local.get $dataLen)
              (i32.const 8192) (i32.const 1024)))
          (call $echo (i32.const 8192) (local.get $status))
          (br $done)))

      ;; 'C' outbound call whose host result exceeds the scratch capacity.
      (if (i32.eq (i32.load8_u (i32.const 0)) (i32.const 0x43))
        (then
          (local.set $status
            (call $call_host
              (i32.const 0) (local.get $actionLen)
              (i32.const 128) (local.get $dataLen)
              (i32.const 8192) (i32.const 4)))
          (if (i32.lt_s (local.get $status) (i32.const 0))
            (then
              (local.set $needed (i32.sub (i32.const 0) (local.get $status)))
              (call $get_response (i32.const 12288))
              (call $echo (i32.const 12288) (local.get $needed))))
          (br $done)))

      ;; 'D' inbound data copy ending exactly at the end of the linear memory.
      (if (i32.eq (i32.load8_u (i32.const 0)) (i32.const 0x44))
        (then
          (call $copy_params (i32.const 1) (i32.const 65532) (i32.const 4))
          (call $echo (i32.const 65532) (i32.const 4))
          (br $done)))

      ;; 'E' requested length exceeds the available host data.
      (if (i32.eq (i32.load8_u (i32.const 0)) (i32.const 0x45))
        (then
          (call $copy_params (i32.const 1) (i32.const 65472) (i32.const 64))
          (call $echo (i32.const 65472) (call $get_size (i32.const 1)))
          (br $done)))

      ;; 'H' zero-length inbound data copy at the exact memory end.
      (if (i32.eq (i32.load8_u (i32.const 0)) (i32.const 0x48))
        (then
          (call $copy_params (i32.const 1) (i32.const 65536) (i32.const 0))
          (call $echo (i32.const 128) (i32.const 4))
          (br $done)))

      ;; 'Z' zero-length response publication.
      (if (i32.eq (i32.load8_u (i32.const 0)) (i32.const 0x5A))
        (then
          (call $set_response (i32.const 4096) (i32.const 0))
          (br $done)))

      ;; 'N' negative inbound copy length.
      (if (i32.eq (i32.load8_u (i32.const 0)) (i32.const 0x4E))
        (then
          (call $copy_params (i32.const 1) (i32.const 128) (i32.const -1))
          (call $echo (i32.const 0) (i32.const 4))
          (br $done)))
      ;; 'O' negative inbound copy destination.
      (if (i32.eq (i32.load8_u (i32.const 0)) (i32.const 0x4F))
        (then
          (call $copy_params (i32.const 1) (i32.const -1) (i32.const 1))
          (call $echo (i32.const 0) (i32.const 4))
          (br $done)))
      ;; 'P' negative response source pointer.
      (if (i32.eq (i32.load8_u (i32.const 0)) (i32.const 0x50))
        (then
          (call $set_response (i32.const -1) (i32.const 16))
          (call $echo (i32.const 0) (i32.const 4))
          (br $done)))
      ;; 'Q' negative response length.
      (if (i32.eq (i32.load8_u (i32.const 0)) (i32.const 0x51))
        (then
          (call $set_response (i32.const 4096) (i32.const -1))
          (call $echo (i32.const 0) (i32.const 4))
          (br $done)))
      ;; 'R' response range outside the linear memory.
      (if (i32.eq (i32.load8_u (i32.const 0)) (i32.const 0x52))
        (then
          (call $set_response (i32.const 0x7FFFFF00) (i32.const 0x200))
          (call $echo (i32.const 0) (i32.const 4))
          (br $done)))
      ;; 'S' inbound copy destination outside the linear memory.
      (if (i32.eq (i32.load8_u (i32.const 0)) (i32.const 0x53))
        (then
          (call $copy_params (i32.const 0) (i32.const 0x7FFFFF00) (i32.const 64))
          (call $echo (i32.const 0) (i32.const 4))
          (br $done)))
      ;; 'T' outbound action pointer outside the linear memory.
      (if (i32.eq (i32.load8_u (i32.const 0)) (i32.const 0x54))
        (then
          (drop
            (call $call_host
              (i32.const -1) (i32.const 4)
              (i32.const 128) (local.get $dataLen)
              (i32.const 8192) (i32.const 1024)))
          (call $echo (i32.const 0) (i32.const 4))
          (br $done)))
      ;; 'U' negative outbound payload length.
      (if (i32.eq (i32.load8_u (i32.const 0)) (i32.const 0x55))
        (then
          (drop
            (call $call_host
              (i32.const 0) (local.get $actionLen)
              (i32.const 128) (i32.const -1)
              (i32.const 8192) (i32.const 1024)))
          (call $echo (i32.const 0) (i32.const 4))
          (br $done)))
      ;; 'V' negative outbound result capacity.
      (if (i32.eq (i32.load8_u (i32.const 0)) (i32.const 0x56))
        (then
          (drop
            (call $call_host
              (i32.const 0) (local.get $actionLen)
              (i32.const 128) (local.get $dataLen)
              (i32.const 8192) (i32.const -1)))
          (call $echo (i32.const 0) (i32.const 4))
          (br $done)))
      ;; 'W' outbound fast-path destination outside the linear memory.
      (if (i32.eq (i32.load8_u (i32.const 0)) (i32.const 0x57))
        (then
          (drop
            (call $call_host
              (i32.const 0) (local.get $actionLen)
              (i32.const 128) (local.get $dataLen)
              (i32.const -1) (i32.const 1024)))
          (call $echo (i32.const 0) (i32.const 4))
          (br $done)))
      ;; 'X' slow-path destination outside the linear memory.
      (if (i32.eq (i32.load8_u (i32.const 0)) (i32.const 0x58))
        (then
          (drop
            (call $call_host
              (i32.const 0) (local.get $actionLen)
              (i32.const 128) (local.get $dataLen)
              (i32.const 8192) (i32.const 4)))
          (call $get_response (i32.const -1))
          (call $echo (i32.const 0) (i32.const 4))
          (br $done)))
      ;; 'Y' outbound payload pointer outside the linear memory.
      (if (i32.eq (i32.load8_u (i32.const 0)) (i32.const 0x59))
        (then
          (drop
            (call $call_host
              (i32.const 0) (local.get $actionLen)
              (i32.const 0x7FF00000) (i32.const 4)
              (i32.const 8192) (i32.const 1024)))
          (call $echo (i32.const 0) (i32.const 4))
          (br $done)))

      ;; Unknown arm: publish the first four action bytes so the mismatch is visible.
      (call $echo (i32.const 0) (i32.const 4))
    )
  )
)
