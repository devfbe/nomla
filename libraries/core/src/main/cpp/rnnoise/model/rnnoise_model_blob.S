/* Embeds weights_blob.bin; NOMLA_RNNOISE_BLOB_PATH is passed by CMake.
 *
 * .hidden is spelled out because -fvisibility=hidden is a compiler flag and does not reach the
 * assembler: without it these two symbols land in .dynsym of every shared library that links
 * this static target, although nothing outside nomla_rnnoise.c may reference them. */
    .section .rodata
    .balign 16
    .globl nomla_rnnoise_model_blob
    .hidden nomla_rnnoise_model_blob
nomla_rnnoise_model_blob:
    .incbin NOMLA_RNNOISE_BLOB_PATH
nomla_rnnoise_model_blob_end:
    .balign 4
    .globl nomla_rnnoise_model_blob_size
    .hidden nomla_rnnoise_model_blob_size
nomla_rnnoise_model_blob_size:
    .int nomla_rnnoise_model_blob_end - nomla_rnnoise_model_blob

/* Without this the linker marks the whole library as needing an executable stack. */
    .section .note.GNU-stack,"",%progbits
