package com.mshykhov.jobhunter.application.common

class ScrapingLeaseLostException : RuntimeException("Scraping lease is stale or invalid")
