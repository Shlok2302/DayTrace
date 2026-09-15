package com.example.voicerecorder.encoder

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

object Mp3Converter {

    private const val TAG =
        "Mp3Converter"

    fun convert(
        inputFile: File,
        outputFile: File,
        onComplete: (Boolean) -> Unit
    ) {

        Log.d(
            TAG,
            "INPUT: ${inputFile.absolutePath}"
        )

        Log.d(
            TAG,
            "INPUT EXISTS: ${inputFile.exists()}"
        )

        Log.d(
            TAG,
            "INPUT SIZE: ${inputFile.length()} bytes"
        )

        Log.d(
            TAG,
            "OUTPUT: ${outputFile.absolutePath}"
        )

        val command =
            "-y " +
                    "-i \"${inputFile.absolutePath}\" " +
                    "-vn " +
                    "-codec:a libmp3lame " +
                    "-b:a 128k " +
                    "\"${outputFile.absolutePath}\""

        Log.d(
            TAG,
            "FFmpeg command: $command"
        )

        try {

            FFmpegKit.executeAsync(
                command
            ) { session ->

                val returnCode =
                    session.returnCode

                val success =
                    ReturnCode.isSuccess(
                        returnCode
                    )

                Log.d(
                    TAG,
                    "FFMPEG RETURN CODE: $returnCode"
                )

                Log.d(
                    TAG,
                    "FFMPEG SUCCESS: $success"
                )

                Log.d(
                    TAG,
                    "OUTPUT EXISTS: ${outputFile.exists()}"
                )

                Log.d(
                    TAG,
                    "OUTPUT SIZE: ${outputFile.length()} bytes"
                )

                onComplete(
                    success &&
                            outputFile.exists() &&
                            outputFile.length() > 0
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "FFmpeg execution failed",
                e
            )

            onComplete(false)
        }
    }
}