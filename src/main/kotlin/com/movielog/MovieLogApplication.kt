package com.movielog

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MovieLogApplication

fun main(args: Array<String>) {
	runApplication<MovieLogApplication>(*args)
}
